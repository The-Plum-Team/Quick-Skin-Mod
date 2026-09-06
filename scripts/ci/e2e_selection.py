#!/usr/bin/env python3
"""Recompute selective E2E from exact Git objects under the executing protected policy.

Callers authenticate repository/run/PR identity and supply immutable base, head and policy
commits independently of the evidence. A decision with no selection requires the existing
complete-profile gate. No candidate Python, build scripts, hooks or worktree files execute here.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "e2e"))
from selection import MAX_CHANGED_PATHS, RoleSelection, ScenarioSelection, Selection, select  # noqa: E402
from scenario_contract import load_contract  # noqa: E402
from module_graph import load_graph, repository_path, unique_object  # noqa: E402

SHA = re.compile(r"[0-9a-f]{40}\Z")
MAX_GIT_BYTES = 2 * 1024 * 1024
MAX_POLICY_BYTES = 1024 * 1024
MAX_MANIFEST_BYTES = 1024 * 1024
GIT_TIMEOUT_SECONDS = 30
POLICY_PATHS = (
    "architecture/modules.json",
    "e2e/scenario-contract.json",
    "e2e/scenario_contract.py",
    "e2e/selection.py",
    "release/release-matrix.json",
    "scripts/architecture/module_graph.py",
    "scripts/ci/e2e_selection.py",
)


class AdmissionError(ValueError):
    """The caller's immutable provenance or executing policy cannot be established."""


class GitReadError(AdmissionError):
    """A bounded, local, read-only Git operation did not complete."""


def canonical(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode()


def _git(repository: Path, *arguments: str, maximum: int = MAX_GIT_BYTES,
         allow_false: bool = False) -> bytes | None:
    env = {key: value for key, value in os.environ.items() if not key.startswith("GIT_")}
    env.update(GIT_CONFIG_NOSYSTEM="1", GIT_CONFIG_GLOBAL=os.devnull,
               GIT_NO_REPLACE_OBJECTS="1", GIT_NO_LAZY_FETCH="1", GIT_TERMINAL_PROMPT="0")
    try:
        process = subprocess.Popen(
            ["git", "-c", "core.hooksPath=" + os.devnull, "-c", "core.fsmonitor=false",
             "-c", "protocol.allow=never", *arguments], cwd=repository, env=env,
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    except OSError as exc:
        raise GitReadError("cannot start the local Git reader") from exc
    timer = threading.Timer(GIT_TIMEOUT_SECONDS, process.kill)
    timer.daemon = True
    timer.start()
    try:
        assert process.stdout is not None
        raw = process.stdout.read(maximum + 1)
        if len(raw) > maximum:
            process.kill()
            raise GitReadError("Git output exceeds its byte limit")
        code = process.wait(timeout=5)
        if allow_false and code == 1:
            return None
        if code != 0:
            raise GitReadError("local Git object read failed or timed out")
        return raw
    finally:
        timer.cancel()
        if process.poll() is None:
            process.kill()
        process.wait(timeout=5)
        if process.stdout is not None:
            process.stdout.close()


def _commit(repository: Path, value: str) -> str:
    if not isinstance(value, str) or not SHA.fullmatch(value):
        raise AdmissionError("selection requires exact lowercase commit SHAs")
    actual = _git(repository, "rev-parse", "--verify", value + "^{commit}", maximum=41)
    if actual != (value + "\n").encode():
        raise AdmissionError("requested object is not the exact commit")
    tree = _git(repository, "rev-parse", value + "^{tree}", maximum=41)
    if tree is None or not SHA.fullmatch(tree.decode("ascii").strip()):
        raise AdmissionError("commit has no canonical tree identity")
    return tree.decode("ascii").strip()


def _blob(repository: Path, commit: str, path: str) -> bytes:
    entry = _git(repository, "ls-tree", "-z", commit, "--", path, maximum=4096)
    if entry is None or not entry.endswith(b"\0") or entry.count(b"\0") != 1:
        raise GitReadError("selection policy file is absent or ambiguous")
    metadata, actual_path = entry[:-1].split(b"\t", 1)
    mode, kind, object_id = metadata.split(b" ")
    if (mode not in (b"100644", b"100755") or kind != b"blob" or actual_path != path.encode()
            or not SHA.fullmatch(object_id.decode("ascii"))):
        raise GitReadError("selection policy must be a regular committed file")
    result = _git(repository, "cat-file", "blob", object_id.decode("ascii"), maximum=MAX_POLICY_BYTES)
    assert result is not None
    return result


def _policy_bytes() -> dict[str, bytes]:
    root = REPO.resolve()
    result = {}
    for name in POLICY_PATHS:
        path = root / name
        if path.resolve() != path or path.is_symlink():
            raise AdmissionError("executing selection policy must not traverse symbolic links")
        with path.open("rb") as stream:
            raw = stream.read(MAX_POLICY_BYTES + 1)
        if not raw or len(raw) > MAX_POLICY_BYTES:
            raise AdmissionError("executing selection policy exceeds its byte limit")
        result[name] = raw
    return result


def _diff_paths(raw: bytes) -> tuple[tuple[str, ...], bool]:
    if not raw:
        return (), True
    if not raw.endswith(b"\0"):
        raise AdmissionError("Git diff is truncated")
    fields = raw[:-1].split(b"\0")
    if len(fields) % 2 or len(fields) // 2 > MAX_CHANGED_PATHS:
        raise AdmissionError("Git diff is malformed or exceeds its entry limit")
    paths: list[str] = []
    regular = True
    for header, encoded in zip(fields[::2], fields[1::2], strict=True):
        match = re.fullmatch(rb":([0-7]{6}) ([0-7]{6}) ([0-9a-f]{40}) ([0-9a-f]{40}) ([AMDT])", header)
        if match is None:
            raise AdmissionError("Git diff contains an unsupported entry")
        try:
            path = repository_path(encoded.decode("utf-8"))
        except (ValueError, UnicodeError) as exc:
            raise AdmissionError("Git diff contains an unsafe path") from exc
        paths.append(path)
        regular &= all(mode in (b"000000", b"100644") for mode in match.group(1, 2))
    if len(paths) != len(set(paths)):
        raise AdmissionError("Git diff contains duplicate paths")
    return tuple(sorted(paths)), regular


@dataclass(frozen=True)
class Admission:
    base_commit: str | None
    head_commit: str
    policy_commit: str
    base_tree: str | None
    head_tree: str
    policy_tree: str
    policy_sha256: str
    diff_sha256: str | None
    diff_complete: bool
    profile: str
    reason: str
    selection: Selection | None

    @property
    def enabled(self) -> bool:
        return self.selection is not None

    def to_dict(self) -> dict[str, Any]:
        return {"schema_version": 1, "input_kind": "git-diff",
                **{name: getattr(self, name) for name in self.__dataclass_fields__ if name != "selection"},
                "selection": self.selection.to_dict() if self.selection is not None else None}

    def to_bytes(self) -> bytes:
        return canonical(self.to_dict())

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.to_bytes()).hexdigest()

    def require_selection(self) -> Selection:
        if self.selection is None:
            raise AdmissionError("this decision requires complete-profile execution")
        return self.selection

    @property
    def contract_sha256(self) -> str:
        return self.require_selection().contract_sha256

    @property
    def runs(self) -> tuple[ScenarioSelection, ...]:
        return self.require_selection().runs

    @property
    def reference_captures(self) -> tuple[str, ...]:
        return self.require_selection().reference_captures

    def role(self, scenario: str, role: str) -> RoleSelection:
        return self.require_selection().role(scenario, role)


def admit(repository: Path, *, base: str | None, head: str, policy: str,
          profile: str = "pr") -> Admission:
    """Derive impact only when the baseline and both trees preserve the executing policy."""
    repository = repository.resolve()
    head_tree = _commit(repository, head)
    policy_tree = _commit(repository, policy)
    executed = _policy_bytes()
    # A caller cannot label candidate code as a protected policy by supplying another SHA.
    if any(_blob(repository, policy, name) != raw for name, raw in executed.items()):
        raise AdmissionError("executing selector differs from the supplied protected policy commit")
    fingerprint = hashlib.sha256(canonical({name: hashlib.sha256(raw).hexdigest()
                                           for name, raw in executed.items()})).hexdigest()
    contract = load_contract(REPO / "e2e/scenario-contract.json")
    graph = load_graph(REPO)
    contract.scenarios_for_profile(profile)
    base_tree = None
    diff_hash = None
    complete = False
    selected = None
    reason = "baseline-unavailable"
    try:
        if base is not None:
            base_tree = _commit(repository, base)
            if _git(repository, "merge-base", "--is-ancestor", base, head, allow_false=True) is None:
                reason = "baseline-is-not-an-ancestor"
            else:
                raw_diff = _git(repository, "diff", "--raw", "--no-renames", "--no-ext-diff",
                                "--no-textconv", "--no-abbrev", "-z", base, head, "--")
                assert raw_diff is not None
                diff_hash = hashlib.sha256(raw_diff).hexdigest()
                paths, regular = _diff_paths(raw_diff)
                complete = True
                if not regular:
                    reason = "non-regular-source-change"
                elif any(_blob(repository, commit, name) != raw for commit in (base, head)
                         for name, raw in executed.items()):
                    reason = "selection-policy-changed"
                else:
                    result = select(contract, graph, paths, profile)
                    reason = result.reason
                    if result.mode == "affected":
                        selected = result
    except (AdmissionError, ValueError, UnicodeError):
        # A missing/truncated/unknown baseline must never turn into a small successful run.
        reason = "unproven-diff-or-policy"
    return Admission(base, head, policy, base_tree, head_tree, policy_tree, fingerprint,
                     diff_hash, complete, profile, reason, selected)


def verify(path: Path, repository: Path, *, base: str | None, head: str, policy: str,
           profile: str = "pr") -> Admission:
    """The expected commits/profile come from the authenticated caller, never from this file."""
    with path.open("rb") as stream:
        raw = stream.read(MAX_MANIFEST_BYTES + 1)
    if not raw or len(raw) > MAX_MANIFEST_BYTES:
        raise AdmissionError("selection admission exceeds its byte limit")
    try:
        supplied = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
        normalized = canonical(supplied)
    except (ValueError, UnicodeError, RecursionError) as exc:
        raise AdmissionError("selection admission must be bounded canonical JSON") from exc
    expected = admit(repository, base=base, head=head, policy=policy, profile=profile)
    if normalized != expected.to_bytes():
        raise AdmissionError("selection admission differs from independently recomputed Git evidence")
    return expected


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=Path.cwd())
    parser.add_argument("--base")
    parser.add_argument("--head", required=True)
    parser.add_argument("--policy", required=True)
    parser.add_argument("--profile", default="pr")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--github-output", type=Path)
    args = parser.parse_args()
    try:
        result = admit(args.repository, base=args.base, head=args.head, policy=args.policy, profile=args.profile)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_bytes(result.to_bytes())
        summary = {"selective": result.enabled, "reason": result.reason,
                   "selection_sha256": result.sha256 if result.enabled else "",
                   "scenarios": ",".join(run.scenario for run in result.selection.runs) if result.selection else ""}
        print(json.dumps(summary, sort_keys=True))
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as stream:
                for key, value in summary.items():
                    stream.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")
    except (OSError, AdmissionError, ValueError) as exc:
        parser.exit(2, f"E2E selection admission failed: {exc}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
