#!/usr/bin/env python3
"""Collect one authenticated clean compatibility wave into a public Pages handoff."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "e2e"))
sys.path.insert(0, str(REPO / "scripts" / "ci"))
sys.path.insert(0, str(REPO / "scripts" / "pages"))
sys.path.insert(0, str(REPO / "scripts" / "release"))

from bounded_zip import ArchiveError, ExtractionLimits, extract_bounded_zip  # noqa: E402
from compatibility_evidence import (  # noqa: E402
    CompatibilityEvidenceError,
    build_bundle,
    carry_forward,
    read_json,
    validate_plan,
)
from mod_compatibility import (  # noqa: E402
    CompatibilityContractError,
    load_contract as load_compatibility_contract,
)
from mod_compatibility_impact import (  # noqa: E402
    ImpactError,
    classify_paths,
    git_diff_paths,
)
from scenario_contract import (  # noqa: E402
    ScenarioContractError,
    load_contract as load_scenario_contract,
)


SOURCE_WORKFLOW = ".github/workflows/mod-compatibility-e2e.yml"
REVIEW_WORKFLOW = ".github/workflows/mod-compatibility-review.yml"
SOURCE_EVENTS = frozenset({"repository_dispatch"})
REVIEW_EVENTS = frozenset({"repository_dispatch", "schedule", "workflow_dispatch"})
MAX_API_PAGES = 4
MAX_ARTIFACTS = 300
MAX_PLAN_ARCHIVE_BYTES = 8 * 1024 * 1024
MAX_REPORT_ARCHIVE_BYTES = 16 * 1024 * 1024
MAX_MARKER_ARCHIVE_BYTES = 1024 * 1024
MAX_CAPSULE_ARCHIVE_BYTES = 512 * 1024 * 1024
MAX_API_RESPONSE_BYTES = 16 * 1024 * 1024
REQUEST_ATTEMPTS = 5
RETRYABLE_STATUSES = frozenset({408, 429, 500, 502, 503, 504})
SHA = re.compile(r"^[0-9a-f]{40}$")
SHA256_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


class CollectionError(RuntimeError):
    """Raised when remote provenance or artifacts cannot be authenticated."""


class _CredentialStrippingRedirect(urllib.request.HTTPRedirectHandler):
    """Never forward the GitHub bearer token to an artifact storage host."""

    def redirect_request(  # type: ignore[override]
        self,
        request: urllib.request.Request,
        file_pointer: Any,
        code: int,
        message: str,
        headers: Any,
        new_url: str,
    ) -> urllib.request.Request | None:
        redirected = super().redirect_request(
            request, file_pointer, code, message, headers, new_url
        )
        if redirected is not None and (
            urllib.parse.urlsplit(request.full_url).netloc.lower()
            != urllib.parse.urlsplit(redirected.full_url).netloc.lower()
        ):
            sensitive = {"authorization", "x-github-api-version"}
            for container in (redirected.headers, redirected.unredirected_hdrs):
                for key in list(container):
                    if key.lower() in sensitive:
                        del container[key]
        return redirected


@dataclass(frozen=True)
class RemoteArtifact:
    artifact_id: int
    name: str
    size: int
    digest: str
    expired: bool
    created_at: str
    run_id: int
    head_branch: str
    head_sha: str

    @classmethod
    def parse(cls, value: Any) -> "RemoteArtifact":
        if not isinstance(value, dict) or not isinstance(value.get("workflow_run"), dict):
            raise CollectionError("artifact metadata is malformed")
        workflow_run = value["workflow_run"]
        artifact_id = value.get("id")
        size = value.get("size_in_bytes")
        run_id = workflow_run.get("id")
        if any(
            isinstance(item, bool) or not isinstance(item, int) or item <= 0
            for item in (artifact_id, size, run_id)
        ):
            raise CollectionError("artifact numeric identity is invalid")
        name = value.get("name")
        digest = value.get("digest")
        head_branch = workflow_run.get("head_branch")
        head_sha = workflow_run.get("head_sha")
        created_at = value.get("created_at")
        if (
            not isinstance(name, str)
            or not name
            or len(name) > 512
            or not isinstance(digest, str)
            or SHA256_DIGEST.fullmatch(digest) is None
            or not isinstance(value.get("expired"), bool)
            or not isinstance(head_branch, str)
            or not head_branch
            or not isinstance(head_sha, str)
            or SHA.fullmatch(head_sha) is None
            or not isinstance(created_at, str)
            or not created_at
        ):
            raise CollectionError("artifact text identity is invalid")
        return cls(
            artifact_id=artifact_id,
            name=name,
            size=size,
            digest=digest,
            expired=value["expired"],
            created_at=created_at,
            run_id=run_id,
            head_branch=head_branch,
            head_sha=head_sha,
        )


class GitHubClient:
    def __init__(self, *, repository: str, token: str, api_url: str) -> None:
        self.repository = repository
        self.token = token
        self.api_url = api_url.rstrip("/")
        self.opener = urllib.request.build_opener(_CredentialStrippingRedirect())

    def _url(self, path: str) -> str:
        return f"{self.api_url}/repos/{self.repository}{path}"

    def _request(
        self,
        path: str,
        *,
        destination: Path | None = None,
        maximum_bytes: int | None = None,
    ) -> Any:
        last_error: BaseException | None = None
        for attempt in range(REQUEST_ATTEMPTS):
            request = urllib.request.Request(
                self._url(path),
                headers={
                    "Accept": "application/vnd.github+json",
                    "Authorization": f"Bearer {self.token}",
                    "X-GitHub-Api-Version": "2022-11-28",
                    "User-Agent": "Quick-Skin-compatibility-Pages/1",
                },
            )
            try:
                with self.opener.open(request, timeout=60) as response:
                    if destination is None:
                        body = response.read(MAX_API_RESPONSE_BYTES + 1)
                        if len(body) > MAX_API_RESPONSE_BYTES:
                            raise CollectionError(
                                f"GitHub API response exceeded its bound for {path}"
                            )
                    else:
                        with destination.open("xb") as output:
                            copied = 0
                            for chunk in iter(lambda: response.read(1024 * 1024), b""):
                                copied += len(chunk)
                                if maximum_bytes is None or copied > maximum_bytes:
                                    raise CollectionError(
                                        f"GitHub artifact download exceeded its bound for {path}"
                                    )
                                output.write(chunk)
                        return None
                if not body:
                    return None
                try:
                    return json.loads(body)
                except json.JSONDecodeError as exc:
                    raise CollectionError(f"GitHub API returned invalid JSON for {path}") from exc
            except CollectionError:
                if destination is not None:
                    destination.unlink(missing_ok=True)
                raise
            except urllib.error.HTTPError as exc:
                detail = exc.read().decode("utf-8", errors="replace")
                retryable = exc.code in RETRYABLE_STATUSES or (
                    exc.code == 403
                    and (
                        exc.headers.get("X-RateLimit-Remaining", "") == "0"
                        or exc.headers.get("Retry-After", "")
                        or "rate limit" in detail.lower()
                    )
                )
                last_error = exc
                message = f"GitHub API {path} failed with HTTP {exc.code}: {detail[:500]}"
            except (urllib.error.URLError, TimeoutError, OSError) as exc:
                if destination is not None:
                    destination.unlink(missing_ok=True)
                retryable = True
                last_error = exc
                message = f"GitHub API {path} failed: {exc}"
            if not retryable or attempt + 1 == REQUEST_ATTEMPTS:
                raise CollectionError(message) from last_error
            time.sleep(min(2**attempt, 20) + (sum(path.encode("utf-8")) % 100) / 100)
        raise CollectionError(f"GitHub API retry loop exhausted for {path}")

    def get_run(self, run_id: int) -> dict[str, Any]:
        payload = self._request(f"/actions/runs/{run_id}")
        if not isinstance(payload, dict):
            raise CollectionError("workflow run response is invalid")
        return payload

    def get_branch_sha(self, branch: str) -> str:
        encoded = urllib.parse.quote(branch, safe="")
        payload = self._request(f"/branches/{encoded}")
        sha = (payload.get("commit") or {}).get("sha") if isinstance(payload, dict) else None
        if not isinstance(sha, str) or SHA.fullmatch(sha) is None:
            raise CollectionError(f"branch {branch!r} has invalid metadata")
        return sha

    def get_artifact(self, artifact_id: int) -> RemoteArtifact:
        return RemoteArtifact.parse(self._request(f"/actions/artifacts/{artifact_id}"))

    def list_run_artifacts(self, run_id: int) -> list[RemoteArtifact]:
        return self._list_artifacts(f"/actions/runs/{run_id}/artifacts", None)

    def list_named_artifacts(self, name: str) -> list[RemoteArtifact]:
        return self._list_artifacts("/actions/artifacts", name)

    def _list_artifacts(self, path: str, name: str | None) -> list[RemoteArtifact]:
        artifacts: list[RemoteArtifact] = []
        for page in range(1, MAX_API_PAGES + 1):
            query: dict[str, Any] = {"per_page": 100, "page": page}
            if name is not None:
                query["name"] = name
            payload = self._request(f"{path}?{urllib.parse.urlencode(query)}")
            batch = payload.get("artifacts") if isinstance(payload, dict) else None
            if not isinstance(batch, list):
                raise CollectionError("artifact inventory response is invalid")
            artifacts.extend(RemoteArtifact.parse(item) for item in batch)
            if len(artifacts) > MAX_ARTIFACTS:
                raise CollectionError("artifact inventory exceeded its bound")
            if len(batch) < 100:
                return artifacts
        raise CollectionError("artifact inventory pagination exceeded its bound")

    def download_and_extract(
        self,
        artifact: RemoteArtifact,
        destination: Path,
        *,
        limits: ExtractionLimits,
    ) -> None:
        current = self.get_artifact(artifact.artifact_id)
        if current != artifact or current.expired:
            raise CollectionError(f"artifact changed before download: {artifact.name}")
        archive = destination.parent / f".{destination.name}.{artifact.artifact_id}.zip"
        if archive.exists() or archive.is_symlink():
            raise CollectionError(f"temporary archive already exists: {archive}")
        try:
            self._request(
                f"/actions/artifacts/{artifact.artifact_id}/zip",
                destination=archive,
                maximum_bytes=artifact.size,
            )
            size = archive.stat().st_size
            digest = hashlib.sha256()
            with archive.open("rb") as input_stream:
                for chunk in iter(lambda: input_stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            if size != artifact.size or f"sha256:{digest.hexdigest()}" != artifact.digest:
                raise CollectionError(f"artifact archive identity drifted: {artifact.name}")
            extract_bounded_zip(archive, destination, limits)
        finally:
            archive.unlink(missing_ok=True)


def _validate_run(
    run: dict[str, Any],
    *,
    repository: str,
    workflow: str,
    events: frozenset[str],
    head_sha: str | None = None,
    conclusions: frozenset[str] = frozenset({"success"}),
) -> str:
    run_sha = run.get("head_sha")
    if (
        run.get("status") != "completed"
        or run.get("conclusion") not in conclusions
        or run.get("event") not in events
        or run.get("path") != workflow
        or run.get("head_branch") != "master"
        or not isinstance(run_sha, str)
        or SHA.fullmatch(run_sha) is None
        or (head_sha is not None and run_sha != head_sha)
        or (run.get("head_repository") or {}).get("full_name") != repository
    ):
        raise CollectionError(f"workflow run {run.get('id')!r} failed provenance validation")
    return run_sha


def _git(repository: Path, *arguments: str) -> str:
    try:
        completed = subprocess.run(
            ["git", *arguments],
            cwd=repository,
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
        )
    except (OSError, subprocess.CalledProcessError) as exc:
        detail = getattr(exc, "stderr", "").strip()
        raise CollectionError(f"git {' '.join(arguments)} failed: {detail or exc}") from exc
    return completed.stdout.strip()


def _fetch_commits(repository: Path, *commits: str) -> None:
    for commit in dict.fromkeys(commits):
        if SHA.fullmatch(commit) is None:
            raise CollectionError("cannot fetch an invalid commit identity")
        _git(repository, "fetch", "--no-tags", "origin", commit)


def _require_nonimpacting_ancestor(repository: Path, base: str, head: str, label: str) -> None:
    if base == head:
        return
    try:
        subprocess.run(
            ["git", "merge-base", "--is-ancestor", base, head],
            cwd=repository,
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
        )
        classification = classify_paths(git_diff_paths(repository, base, head))
    except (OSError, subprocess.CalledProcessError, ImpactError) as exc:
        raise CollectionError(f"cannot authenticate {label} ancestry: {exc}") from exc
    if classification.compatibility_required:
        raise CollectionError(
            f"{label} advanced through compatibility-impacting paths: "
            f"{list(classification.impact_paths)}"
        )


def _one_artifact(
    artifacts: list[RemoteArtifact],
    *,
    name: str,
    run_id: int | None = None,
    maximum_size: int,
) -> RemoteArtifact:
    selected = [
        artifact
        for artifact in artifacts
        if artifact.name == name
        and not artifact.expired
        and artifact.size <= maximum_size
        and (run_id is None or artifact.run_id == run_id)
    ]
    if len(selected) != 1:
        raise CollectionError(f"expected exactly one authenticated {name}: {len(selected)}")
    return selected[0]


def _select_review_artifact(
    api: GitHubClient,
    *,
    name: str,
    repository: str,
    current_sha: str,
    repository_root: Path,
    maximum_size: int,
    required_run_id: int | None = None,
    required_owner_sha: str | None = None,
) -> tuple[RemoteArtifact, str]:
    candidates = sorted(
        (
            artifact
            for artifact in api.list_named_artifacts(name)
            if artifact.name == name
            and not artifact.expired
            and artifact.size <= maximum_size
            and (required_run_id is None or artifact.run_id == required_run_id)
        ),
        key=lambda artifact: (artifact.created_at, artifact.artifact_id),
        reverse=True,
    )
    authenticated: list[tuple[RemoteArtifact, str]] = []
    for artifact in candidates:
        try:
            run_sha = _validate_run(
                api.get_run(artifact.run_id),
                repository=repository,
                workflow=REVIEW_WORKFLOW,
                events=REVIEW_EVENTS,
                head_sha=required_owner_sha,
                conclusions=frozenset({"success", "failure"}),
            )
            _fetch_commits(repository_root, run_sha)
            _require_nonimpacting_ancestor(
                repository_root, run_sha, current_sha, "review implementation"
            )
        except CollectionError:
            continue
        authenticated.append((artifact, run_sha))
    if not authenticated:
        raise CollectionError(
            f"no authenticated review artifact exists for {name}"
        )
    return authenticated[0]


def _write_json(path: Path, value: Any) -> None:
    if path.exists() or path.is_symlink():
        raise CollectionError(f"refusing to replace generated metadata {path}")
    path.write_text(
        json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n",
        encoding="utf-8",
    )


def collect(
    *,
    api: GitHubClient,
    repository: str,
    source_run_id: int,
    current_implementation_sha: str,
    publication_run_id: int,
    output_root: Path,
    repository_root: Path,
) -> tuple[Path, dict[str, Any]]:
    current_implementation_sha = current_implementation_sha.strip()
    if SHA.fullmatch(current_implementation_sha) is None:
        raise CollectionError("current implementation SHA is invalid")
    source_run = api.get_run(source_run_id)
    source_implementation_sha = _validate_run(
        source_run,
        repository=repository,
        workflow=SOURCE_WORKFLOW,
        events=SOURCE_EVENTS,
    )
    _fetch_commits(repository_root, source_implementation_sha, current_implementation_sha)
    _require_nonimpacting_ancestor(
        repository_root,
        source_implementation_sha,
        current_implementation_sha,
        "compatibility implementation",
    )
    source_artifacts = api.list_run_artifacts(source_run_id)
    plan_artifact = _one_artifact(
        source_artifacts,
        name="mod-compatibility-plan",
        run_id=source_run_id,
        maximum_size=MAX_PLAN_ARCHIVE_BYTES,
    )

    temporary_root = Path(tempfile.mkdtemp(prefix="quick-skin-compatibility-publication."))
    try:
        plan_root = temporary_root / "plan"
        api.download_and_extract(
            plan_artifact,
            plan_root,
            limits=ExtractionLimits(
                archive_bytes=MAX_PLAN_ARCHIVE_BYTES,
                entries=4,
                total_bytes=8 * 1024 * 1024,
                file_bytes=8 * 1024 * 1024,
                compression_ratio=200,
            ),
        )
        plan_path = plan_root / "mod-compatibility-plan.json"
        plan = read_json(plan_path, "compatibility plan")
        try:
            compatibility_contract = load_compatibility_contract()
            scenario_contract = load_scenario_contract()
        except (CompatibilityContractError, ScenarioContractError, OSError) as exc:
            raise CollectionError(
                f"cannot load compatibility contracts: {exc}"
            ) from exc
        identity, plan_rows, _not_applicable = validate_plan(
            plan,
            compatibility_run_id=source_run_id,
            contract=compatibility_contract,
            scenario_contract=scenario_contract,
        )
        _fetch_commits(
            repository_root,
            identity["source_sha"],
            identity["target_sha"],
        )
        if _git(repository_root, "rev-parse", f"{identity['source_sha']}^{{tree}}") != _git(
            repository_root, "rev-parse", f"{identity['target_sha']}^{{tree}}"
        ):
            raise CollectionError("compatibility source and target trees differ")
        current_target_sha = api.get_branch_sha(identity["branch"])
        _fetch_commits(repository_root, current_target_sha)
        _require_nonimpacting_ancestor(
            repository_root,
            identity["target_sha"],
            current_target_sha,
            "release branch",
        )

        completion_name = f"mod-compatibility-review-complete-{source_run_id}"
        completion_artifact, completion_owner_sha = _select_review_artifact(
            api,
            name=completion_name,
            repository=repository,
            current_sha=current_implementation_sha,
            repository_root=repository_root,
            maximum_size=MAX_MARKER_ARCHIVE_BYTES,
            required_owner_sha=source_implementation_sha,
        )
        completion_root = temporary_root / "source-completion"
        api.download_and_extract(
            completion_artifact,
            completion_root,
            limits=ExtractionLimits(
                archive_bytes=MAX_MARKER_ARCHIVE_BYTES,
                entries=2,
                total_bytes=MAX_MARKER_ARCHIVE_BYTES,
                file_bytes=MAX_MARKER_ARCHIVE_BYTES,
                compression_ratio=200,
            ),
        )
        completion = read_json(
            completion_root / "mod-compatibility-review-complete.json",
            "source completion marker",
            maximum_bytes=MAX_MARKER_ARCHIVE_BYTES,
        )
        if (
            not isinstance(completion, dict)
            or set(completion)
            != {
                "schema_version",
                "kind",
                "source_run_id",
                "implementation_sha",
                "lane_count",
            }
            or completion.get("schema_version") != 1
            or completion.get("kind")
            != "quick-skin-mod-compatibility-review-complete"
            or completion.get("source_run_id") != source_run_id
            or completion.get("implementation_sha") != source_implementation_sha
            or completion.get("lane_count") != len(plan_rows)
            or completion_owner_sha != source_implementation_sha
        ):
            raise CollectionError("source completion marker is invalid")

        lanes_root = temporary_root / "lanes"
        lanes_root.mkdir()
        for lane_id in sorted(plan_rows):
            lane_root = lanes_root / lane_id
            lane_root.mkdir()
            capsule_name = f"mod-compatibility-review-input-{source_run_id}-{lane_id}"
            capsule_artifact = _one_artifact(
                source_artifacts,
                name=capsule_name,
                run_id=source_run_id,
                maximum_size=MAX_CAPSULE_ARCHIVE_BYTES,
            )
            api.download_and_extract(
                capsule_artifact,
                lane_root / "capsule",
                limits=ExtractionLimits(
                    archive_bytes=MAX_CAPSULE_ARCHIVE_BYTES,
                    entries=520,
                    total_bytes=MAX_CAPSULE_ARCHIVE_BYTES,
                    file_bytes=32 * 1024 * 1024,
                    compression_ratio=200,
                ),
            )
            lane_completion_name = (
                f"mod-compatibility-lane-complete-{source_run_id}-{lane_id}"
            )
            lane_completion_artifact, review_owner_sha = _select_review_artifact(
                api,
                name=lane_completion_name,
                repository=repository,
                current_sha=current_implementation_sha,
                repository_root=repository_root,
                maximum_size=MAX_MARKER_ARCHIVE_BYTES,
                required_owner_sha=source_implementation_sha,
            )
            if review_owner_sha != source_implementation_sha:
                raise CollectionError(f"lane {lane_id} reviewer implementation drifted")
            report_name = f"mod-compatibility-review-{source_run_id}-{lane_id}"
            report_artifact, report_owner_sha = _select_review_artifact(
                api,
                name=report_name,
                repository=repository,
                current_sha=current_implementation_sha,
                repository_root=repository_root,
                maximum_size=MAX_REPORT_ARCHIVE_BYTES,
                required_run_id=lane_completion_artifact.run_id,
                required_owner_sha=source_implementation_sha,
            )
            if report_owner_sha != review_owner_sha:
                raise CollectionError(f"lane {lane_id} report owner drifted")
            api.download_and_extract(
                lane_completion_artifact,
                lane_root / "completion",
                limits=ExtractionLimits(
                    archive_bytes=MAX_MARKER_ARCHIVE_BYTES,
                    entries=2,
                    total_bytes=MAX_MARKER_ARCHIVE_BYTES,
                    file_bytes=MAX_MARKER_ARCHIVE_BYTES,
                    compression_ratio=200,
                ),
            )
            api.download_and_extract(
                report_artifact,
                lane_root / "report",
                limits=ExtractionLimits(
                    archive_bytes=MAX_REPORT_ARCHIVE_BYTES,
                    entries=8,
                    total_bytes=MAX_REPORT_ARCHIVE_BYTES,
                    file_bytes=8 * 1024 * 1024,
                    compression_ratio=200,
                ),
            )
            _write_json(
                lane_root / "metadata.json",
                {"review_run_id": lane_completion_artifact.run_id},
            )

        built_root = temporary_root / "built"
        bundle = build_bundle(
            plan_path=plan_path,
            lanes_root=lanes_root,
            output_root=built_root,
            repository=repository,
            compatibility_run_id=source_run_id,
            implementation_sha=source_implementation_sha,
            publication_run_id=publication_run_id,
            scenario_contract_path=REPO / "e2e/scenario-contract.json",
            compatibility_contract_path=REPO / "e2e/mod-compatibility-contract.json",
        )
        final_root = temporary_root / "final"
        if current_target_sha == identity["target_sha"]:
            final_root.mkdir()
            shutil.copytree(bundle, final_root / identity["branch"])
        else:
            carry_forward(
                evidence_root=built_root,
                output_root=final_root,
                branch=identity["branch"],
                coverage_sha=current_target_sha,
                expected_repository=repository,
                scenario_contract_path=REPO / "e2e/scenario-contract.json",
                compatibility_contract_path=REPO / "e2e/mod-compatibility-contract.json",
            )
        if output_root.is_symlink():
            raise CollectionError("compatibility output root cannot be a symlink")
        destination_root = output_root.resolve()
        if destination_root.exists() and not destination_root.is_dir():
            raise CollectionError("compatibility output root is not a directory")
        destination_root.mkdir(parents=True, exist_ok=True)
        destination = destination_root / identity["branch"]
        if destination.exists() or destination.is_symlink():
            raise CollectionError(f"refusing to replace {destination}")
        os.replace(final_root / identity["branch"], destination)
        summary = {
            "branch": identity["branch"],
            "target_sha": identity["target_sha"],
            "coverage_sha": current_target_sha,
            "compatibility_run_id": source_run_id,
            "artifact_name": f"pages-mod-compatibility-{identity['branch']}",
            "lane_count": len(plan_rows),
            "publication_run_id": publication_run_id,
        }
        return destination, summary
    finally:
        shutil.rmtree(temporary_root, ignore_errors=True)


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--source-run-id", type=int, required=True)
    parser.add_argument("--current-implementation-sha", required=True)
    parser.add_argument("--publication-run-id", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository-root", type=Path, default=REPO)
    parser.add_argument("--github-output", type=Path)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        repository = args.repository.strip()
        if REPOSITORY.fullmatch(repository) is None:
            raise CollectionError("repository must use the owner/name form")
        if args.source_run_id <= 0 or args.publication_run_id <= 0:
            raise CollectionError("run IDs must be positive integers")
        token = os.environ.get("GH_TOKEN", "")
        if not token:
            raise CollectionError("GH_TOKEN is required")
        api = GitHubClient(
            repository=repository,
            token=token,
            api_url=os.environ.get("GITHUB_API_URL", "https://api.github.com"),
        )
        destination, summary = collect(
            api=api,
            repository=repository,
            source_run_id=args.source_run_id,
            current_implementation_sha=args.current_implementation_sha,
            publication_run_id=args.publication_run_id,
            output_root=args.output,
            repository_root=args.repository_root.resolve(),
        )
        if args.github_output is not None:
            with args.github_output.open("a", encoding="utf-8") as output:
                for key in (
                    "branch",
                    "target_sha",
                    "coverage_sha",
                    "compatibility_run_id",
                    "artifact_name",
                    "lane_count",
                ):
                    output.write(f"{key}={summary[key]}\n")
        print(json.dumps({**summary, "output": str(destination)}, sort_keys=True))
        return 0
    except (
        ArchiveError,
        CollectionError,
        CompatibilityContractError,
        CompatibilityEvidenceError,
        ImpactError,
        OSError,
    ) as exc:
        print(f"compatibility collection error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
