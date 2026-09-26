#!/usr/bin/env python3
"""Persist upload intent in the draft release; moderation never authorizes a retry.

Every caller that writes a release body must hold the Actions release-publish lock.
The hidden comment survives runner loss and leaves the exact release asset set intact.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Any, Callable

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/ci"))

from feature_coverage_github import Api
from matrix import gha_matrix, load_matrix, select_release_target
from reconcile_publication import (PublicationPendingError, Reconciliation, inspect_curseforge,
                                   inspect_modrinth, load_expected)

MARKER = "<!-- quick-skin-publication-state:v1\n"
END = "\n-->"
MAX_BODY_BYTES = 60_000
MAX_RELEASE_PAGES = 10
TAG = re.compile(r"mc[0-9]+(?:\.[0-9]+){1,2}-v[0-9A-Za-z][0-9A-Za-z._-]{0,100}")
SHA = re.compile(r"[0-9a-f]{40}")
DIGEST = re.compile(r"[0-9a-f]{64}")
STATES = frozenset({"unstarted", "uploading", "pending", "verified"})


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        require(key not in value, "duplicate publication state key")
        value[key] = item
    return value


def validate(state: Any) -> dict[str, Any]:
    require(isinstance(state, dict) and set(state) == {
        "schema_version", "tag", "source_sha", "producer_sha", "run_id", "artifact_id",
        "artifact_digest", "manifest_sha256", "target", "rows",
    }, "invalid publication state fields")
    require(type(state["schema_version"]) is int and state["schema_version"] == 1,
            "unsupported publication state schema")
    require(isinstance(state["tag"], str) and TAG.fullmatch(state["tag"]) is not None,
            "invalid publication tag")
    require(isinstance(state["target"], str) and state["tag"].startswith(f"mc{state['target']}-v"),
            "publication target differs from tag")
    for key in ("source_sha", "producer_sha"):
        require(isinstance(state[key], str) and SHA.fullmatch(state[key]) is not None,
                f"invalid {key}")
    for key in ("run_id", "artifact_id"):
        require(type(state[key]) is int and 0 < state[key] < 10**16, f"invalid {key}")
    require(isinstance(state["manifest_sha256"], str)
            and DIGEST.fullmatch(state["manifest_sha256"]) is not None, "invalid manifest digest")
    require(isinstance(state["artifact_digest"], str)
            and re.fullmatch(r"sha256:[0-9a-f]{64}", state["artifact_digest"]) is not None,
            "invalid artifact digest")
    require(isinstance(state["rows"], dict) and 0 < len(state["rows"]) <= 8,
            "invalid publication row inventory")
    for key, row in state["rows"].items():
        require(isinstance(key, str) and re.fullmatch(r"[a-z0-9_.-]{1,160}", key) is not None,
                "invalid publication row identity")
        require(isinstance(row, dict) and set(row) == {"state", "remote_id"}
                and row["state"] in STATES, "invalid publication row")
        remote_id = row["remote_id"]
        require(remote_id is None or isinstance(remote_id, str)
                and re.fullmatch(r"[A-Za-z0-9]{1,80}", remote_id) is not None,
                "invalid remote file ID")
        require((row["state"] == "verified") == (remote_id is not None),
                "only a verified publication can bind a remote file ID")
    return state


def decode(body: str) -> dict[str, Any] | None:
    require(isinstance(body, str) and len(body.encode()) <= MAX_BODY_BYTES,
            "release body exceeds publication state limit")
    if "<!-- quick-skin-publication-state:" not in body:
        return None
    require(body.count("<!-- quick-skin-publication-state:") == 1 and MARKER in body,
            "duplicate or unsupported publication state marker")
    payload = body.split(MARKER, 1)[1]
    require(payload.endswith(END), "publication state must be the final release body block")
    return validate(json.loads(payload[:-len(END)], object_pairs_hook=unique_object))


def encode(body: str, state: dict[str, Any]) -> str:
    previous = decode(body)
    notes = body.split(MARKER, 1)[0].rstrip() if previous is not None else body.rstrip()
    result = notes + "\n\n" + MARKER + json.dumps(validate(state), sort_keys=True,
                                                  separators=(",", ":")) + END
    require(len(result.encode()) <= MAX_BODY_BYTES, "release body has no room for publication state")
    return result


def release_id(api: Api, tag: str) -> int:
    # GitHub's tag lookup omits drafts, and every ledger write happens while the release is one.
    matches: list[int] = []
    for page in range(1, MAX_RELEASE_PAGES + 1):
        releases = api.json(f"releases?per_page=100&page={page}")
        require(isinstance(releases, list) and len(releases) <= 100, "invalid release inventory")
        for release in releases:
            require(isinstance(release, dict) and type(release.get("id")) is int and release["id"] > 0,
                    "malformed release inventory")
            if release.get("tag_name") == tag:
                matches.append(release["id"])
        if len(releases) < 100:
            require(len(matches) == 1, "release tag has no single GitHub release")
            return matches[0]
    raise ValueError("release inventory exceeds its pagination limit")


def read_release(api: Api, tag: str) -> dict[str, Any]:
    require(TAG.fullmatch(tag) is not None, "invalid release tag")
    identifier = release_id(api, tag)
    release = api.json(f"releases/{identifier}")
    require(isinstance(release, dict) and release.get("id") == identifier
            and release.get("tag_name") == tag
            and type(release.get("id")) is int and release["id"] > 0
            and type(release.get("draft")) is bool, "GitHub release identity differs")
    decode(release.get("body") or "")
    return release


def save(api: Api, release: dict[str, Any], state: dict[str, Any]) -> None:
    body = release.get("body") or ""
    updated = encode(body, state)
    if updated == body:
        return
    require(release["draft"] is True, "cannot change the ledger of a published release")
    fresh = read_release(api, state["tag"])
    require(fresh["id"] == release["id"] and fresh["draft"] is True
            and (fresh.get("body") or "") == body, "release body changed; retry under the publication lock")
    # The lock is the serialization authority, not an undocumented HTTP compare-and-swap.
    # A draft edit that omits tag_name detaches the draft from its tag, so always restate it.
    payload = {"body": updated, "tag_name": state["tag"]}
    subprocess.run(["gh", "api", "--method", "PATCH", api.prefix + f"releases/{release['id']}",
                    "--input", "-"], input=json.dumps(payload), text=True, check=True,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=60)
    require((read_release(api, state["tag"]).get("body") or "") == updated,
            "publication state write was not confirmed")


def publication_rows(matrix: dict[str, Any], manifest: dict[str, Any]) -> list[dict[str, Any]]:
    versions = manifest.get("release", {}).get("minecraft_versions")
    require(isinstance(versions, list) and len(versions) == 1, "publication needs one exact target")
    selected = select_release_target(matrix, versions[0])
    return gha_matrix(selected, "publications", manifest["mod_version"])["include"]


def bind(state: dict[str, Any], matrix: dict[str, Any], manifest_path: Path) -> list[dict[str, Any]]:
    validate(state)
    raw = manifest_path.read_bytes()
    manifest = json.loads(raw)
    rows = publication_rows(matrix, manifest)
    require(hashlib.sha256(raw).hexdigest() == state["manifest_sha256"]
            and manifest["git_commit"] == state["source_sha"]
            and manifest["release"]["tag"] == state["tag"]
            and manifest["release"]["minecraft_versions"] == [state["target"]]
            and set(state["rows"]) == {row["id"] for row in rows},
            "publication ledger differs from the immutable bundle")
    return rows


def new_state(manifest_path: Path, matrix: dict[str, Any], *, producer_sha: str,
              run_id: int, artifact: dict[str, Any]) -> dict[str, Any]:
    raw = manifest_path.read_bytes()
    manifest = json.loads(raw)
    rows = publication_rows(matrix, manifest)
    state = {
        "schema_version": 1, "tag": manifest["release"]["tag"],
        "target": manifest["release"]["minecraft_versions"][0],
        "source_sha": manifest["git_commit"], "producer_sha": producer_sha, "run_id": run_id,
        "artifact_id": artifact["id"], "artifact_digest": artifact["digest"],
        "manifest_sha256": hashlib.sha256(raw).hexdigest(),
        "rows": {row["id"]: {"state": "unstarted", "remote_id": None} for row in rows},
    }
    return validate(state)


def observe(row: dict[str, Any], inspector: Callable[[], Reconciliation]) -> dict[str, Any]:
    try:
        result = inspector()
    except PublicationPendingError:
        # An already indexed, unapproved file also fences an upload from an older run.
        require(row["state"] != "verified", "a previously verified file is no longer approved")
        return {"state": "pending", "remote_id": None}
    if result.publish:
        require(row["state"] != "verified", "a previously verified file is no longer observable")
        return dict(row)
    require(result.remote_id is not None, "verified marketplace file has no stable ID")
    require(row["remote_id"] in (None, result.remote_id), "verified marketplace file ID changed")
    return {"state": "verified", "remote_id": result.remote_id}


def begin(row: dict[str, Any]) -> tuple[dict[str, Any], bool]:
    if row["state"] != "unstarted":
        return dict(row), False
    return {"state": "uploading", "remote_id": None}, True


def accept(row: dict[str, Any]) -> dict[str, Any]:
    require(row["state"] in {"uploading", "pending", "verified"}, "upload has no persisted intent")
    return {"state": "pending", "remote_id": None} if row["state"] == "uploading" else dict(row)


def inspector_for(matrix: dict[str, Any], manifest: Path, stage: Path,
                  row: dict[str, Any]) -> Callable[[], Reconciliation]:
    expected = load_expected(manifest, stage, row["artifact_node"])
    if row["marketplace"] == "modrinth":
        return lambda: inspect_modrinth(expected, str(matrix["project"]["modrinth_id"]),
                                       row["publication_id"], os.environ.get("MODRINTH_TOKEN", ""))
    return lambda: inspect_curseforge(expected, int(matrix["project"]["curseforge_id"]))


def check_all(state: dict[str, Any], matrix: dict[str, Any], manifest: Path,
              stage: Path) -> dict[str, Any]:
    result = copy.deepcopy(state)
    for row in bind(state, matrix, manifest):
        result["rows"][row["id"]] = observe(state["rows"][row["id"]],
                                           inspector_for(matrix, manifest, stage, row))
    return validate(result)


def ready(state: dict[str, Any]) -> bool:
    return all(row["state"] == "verified" for row in validate(state)["rows"].values())


def report(state: dict[str, Any]) -> str:
    lines = [f"{state['tag']}: {'verified' if ready(state) else 'publication pending'}"]
    lines.extend(f"- {key}: {row['state']}" for key, row in sorted(state["rows"].items()))
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("register", "begin", "accept", "check"))
    parser.add_argument("--tag", required=True)
    parser.add_argument("--artifact-id", type=int)
    parser.add_argument("--row")
    parser.add_argument("--stage", type=Path, default=Path("build/release"))
    parser.add_argument("--github-output", type=Path)
    parser.add_argument("--record", type=Path)
    args = parser.parse_args()
    try:
        api = Api(os.environ["GITHUB_REPOSITORY"])
        matrix = load_matrix(ROOT / "release/release-matrix.json")
        manifest = args.stage / "artifacts.json"
        release = read_release(api, args.tag)
        state = decode(release.get("body") or "")
        publish = False
        if args.command == "register":
            if state is not None:
                bind(state, matrix, manifest)
            if release["draft"]:
                artifact = api.artifact(args.artifact_id)
                require(artifact.get("expired") is False
                        and artifact.get("name") in {f"release-{args.tag}", f"release-recovery-{args.tag}"}
                        and artifact.get("workflow_run", {}).get("id") == int(os.environ["GITHUB_RUN_ID"])
                        and artifact["workflow_run"].get("head_sha") == os.environ["GITHUB_SHA"],
                        "release archive has another workflow owner")
                previous = state
                state = new_state(manifest, matrix, producer_sha=os.environ["GITHUB_SHA"],
                                  run_id=int(os.environ["GITHUB_RUN_ID"]), artifact=artifact)
                if previous is not None:
                    # A newly authenticated preparation may refresh the archive/producer binding,
                    # but must preserve every fence for this exact manifest, including uncertain uploads.
                    state["rows"] = copy.deepcopy(previous["rows"])
        require(state is not None and state["tag"] == args.tag, "release has no matching upload ledger")
        rows = bind(state, matrix, manifest)
        if args.command in {"begin", "accept"}:
            matches = [row for row in rows if row["id"] == args.row]
            require(len(matches) == 1, "unknown publication row")
            row = matches[0]
            current = state["rows"][args.row]
            if args.command == "begin":
                current = observe(current, inspector_for(matrix, manifest, args.stage, row))
                current, publish = begin(current)
            else:
                current = accept(current)
                # Persist successful acceptance before a fallible public API read.
                state["rows"][args.row] = current
                save(api, release, state)
                release = read_release(api, args.tag)
                current = observe(current, inspector_for(matrix, manifest, args.stage, row))
            state["rows"][args.row] = current
        elif args.command == "check":
            state = check_all(state, matrix, manifest, args.stage)
        save(api, release, state)
        if args.github_output:
            with args.github_output.open("a", encoding="utf-8") as output:
                output.write(f"publish={str(publish).lower()}\nready={str(ready(state)).lower()}\n")
        if args.record:
            args.record.parent.mkdir(parents=True, exist_ok=True)
            args.record.write_text(json.dumps(state, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        summary = report(state)
        print(summary)
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a", encoding="utf-8") as output:
                output.write(summary + "\n")
        return 0
    except (ValueError, RuntimeError, OSError, KeyError, TypeError, subprocess.SubprocessError) as exc:
        parser.exit(1, f"publication state failed: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
