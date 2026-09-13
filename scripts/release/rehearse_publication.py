#!/usr/bin/env python3
"""Exercise publication contracts and crash recovery offline with the actual staged bundle.

Only the transport is simulated. The production asset reconciler, ledger persistence,
marketplace classifiers and completion gate run unchanged; unexpected I/O fails closed.
This cannot certify a provider's live availability or moderation time.
"""
from __future__ import annotations

import argparse
import copy
import json
import subprocess
import tempfile
from pathlib import Path
from typing import Any
from unittest.mock import patch

import github_release as github
import publication_state as ledger
import reconcile_publication as marketplace
from generate_sbom import validate_cyclonedx
from matrix import load_matrix


class InterruptedUpload(RuntimeError):
    pass


class SimulatedGitHub:
    repository = "rehearsal/offline"
    prefix = "repos/rehearsal/offline/"

    def __init__(self, contract: github.ReleaseContract):
        self.contract = contract
        self.release: dict[str, Any] | None = None
        self.assets: dict[str, bytes] = {}
        self.uploads: list[str] = []
        self.interrupt = True

    def json(self, endpoint: str) -> dict[str, Any]:
        ledger.require(endpoint == f"releases/tags/{self.contract.tag}" and self.release is not None,
                       "unexpected simulated release lookup")
        return copy.deepcopy(self.release)

    def command(self, command: list[str], **kwargs: Any) -> subprocess.CompletedProcess:
        output: Any = ""
        if command == ["git", "rev-parse", f"{self.contract.tag}^{{commit}}"]:
            output = self.contract.commit
        elif command[:3] == ["gh", "release", "create"]:
            ledger.require(self.release is None and "--draft" in command
                           and "--verify-tag" in command, "unsafe simulated release create")
            self.release = {"id": 1, "tag_name": self.contract.tag, "draft": True, "body": "Release notes"}
        elif command[:3] == ["gh", "release", "view"]:
            if self.release is None:
                return subprocess.CompletedProcess(command, 1, stdout="", stderr="release not found")
            output = json.dumps({"databaseId": 1, "tagName": self.contract.tag,
                                 "isDraft": self.release["draft"]})
        elif command[:3] == ["gh", "release", "upload"]:
            asset = Path(command[4])
            name = asset.name.replace(" ", ".").strip(".")
            ledger.require(name not in self.assets, "rehearsal attempted a duplicate asset upload")
            self.assets[name] = asset.read_bytes()
            self.uploads.append(name)
            if self.interrupt:
                self.interrupt = False
                raise InterruptedUpload("connection lost after GitHub accepted the first asset")
        elif command[:3] == ["gh", "release", "edit"]:
            ledger.require(self.release is not None and "--draft=false" in command,
                           "unexpected simulated release mutation")
            ledger.require(ledger.ready(ledger.decode(self.release["body"])),
                           "GitHub publication preceded complete marketplace verification")
            self.release["draft"] = False
        elif command[:4] == ["gh", "api", "--method", "PATCH"]:
            ledger.require(command[4] == self.prefix + "releases/1" and self.release is not None,
                           "unexpected simulated ledger mutation")
            payload = json.loads(kwargs["input"])
            ledger.require(set(payload) == {"body"}, "ledger changed non-body release fields")
            ledger.decode(payload["body"])
            self.release["body"] = payload["body"]
        elif command[:4] == ["gh", "api", "--paginate", "--slurp"]:
            output = json.dumps([[{"id": i, "name": name}
                                  for i, name in enumerate(self.assets, start=1)]])
        elif command[:2] == ["gh", "api"] and command[-1].startswith(self.prefix + "releases/assets/"):
            index = int(command[-1].rsplit("/", 1)[1]) - 1
            output = list(self.assets.values())[index]
        else:
            raise ValueError(f"unexpected I/O in publication rehearsal: {command[:3]}")
        return subprocess.CompletedProcess(command, 0, stdout=output, stderr="")


class SimulatedMarketplaces:
    def __init__(self, matrix: dict[str, Any], manifest: Path, stage: Path):
        self.matrix, self.manifest, self.stage = matrix, manifest, stage
        self.accepted: dict[str, dict[str, Any]] = {}
        self.visible = False
        self.approved = False
        self.downloads: dict[str, bytes] = {}

    def upload(self, row: dict[str, Any]) -> None:
        ledger.require(row["id"] not in self.accepted, "rehearsal attempted a duplicate marketplace upload")
        self.accepted[row["id"]] = row

    def request(self, url: str, headers: dict[str, str], **kwargs: Any) -> Any:
        versions, files = [], []
        for i, row in enumerate(self.accepted.values(), start=1):
            expected = marketplace.load_expected(self.manifest, self.stage, row["artifact_node"])
            if row["marketplace"] == "modrinth":
                versions.append({"id": f"MR{i}", "project_id": self.matrix["project"]["modrinth_id"],
                                 "version_number": row["publication_id"], "files": [{
                                     "filename": expected.filename, "size": expected.bytes,
                                     "hashes": {"sha512": expected.sha512}}]})
            elif self.visible:
                identifier = 9000000 + i
                files.append({"id": identifier, "projectId": self.matrix["project"]["curseforge_id"],
                              "fileName": expected.filename, "fileLength": expected.bytes,
                              "status": 4 if self.approved else 1})
                self.downloads[marketplace.curseforge_download_url(identifier, expected.filename)] = expected.path.read_bytes()
        if url.startswith(marketplace.MODRINTH_API + "/version_file/"):
            digest = url.split("/version_file/", 1)[1].split("?", 1)[0]
            return next((version for version in versions
                         if version["files"][0]["hashes"]["sha512"] == digest), None)
        if url == f"{marketplace.MODRINTH_API}/project/{self.matrix['project']['modrinth_id']}/version":
            return versions
        if url == f"{marketplace.CURSEFORGE_PUBLIC_API}/mods/{self.matrix['project']['curseforge_id']}/files?pageIndex=0&pageSize=50":
            return {"data": files, "pagination": {"totalCount": len(files)}}
        raise ValueError("unexpected marketplace request in publication rehearsal")

    def download(self, url: str, maximum: int) -> bytes:
        payload = self.downloads[url]
        ledger.require(len(payload) <= maximum, "simulated CDN exceeded expected file size")
        return payload


def rehearse(matrix: dict[str, Any], stage: Path) -> dict[str, Any]:
    manifest_path = stage / "artifacts.json"
    manifest = json.loads(manifest_path.read_bytes())
    validate_cyclonedx(json.loads((stage / manifest["sbom"]["path"]).read_bytes()))
    contract = github.load_contract(manifest_path, stage, manifest["release"]["tag"], manifest["git_commit"])
    state = ledger.new_state(manifest_path, matrix, producer_sha=manifest["git_commit"], run_id=1,
                             artifact={"id": 1, "digest": "sha256:" + "a" * 64})
    service = SimulatedGitHub(contract)
    markets = SimulatedMarketplaces(matrix, manifest_path, stage)
    original_curseforge = marketplace.inspect_curseforge
    with tempfile.TemporaryDirectory(prefix="publication-rehearsal-") as temporary, patch.object(
        github, "run", side_effect=service.command
    ), patch.object(ledger.subprocess, "run", side_effect=service.command), patch.object(
        marketplace, "request_json", side_effect=markets.request
    ), patch.object(ledger, "inspect_curseforge", side_effect=lambda expected, project:
                   original_curseforge(expected, project, fetch_bytes=markets.download)):
        directory = Path(temporary)
        checksums = github.write_checksums(contract, directory)
        try:
            github.stage_release(service.repository, contract, "Rehearsal", manifest_path, checksums)
        except InterruptedUpload:
            pass
        else:
            raise ValueError("rehearsal did not exercise interrupted GitHub staging")
        for _ in range(2):
            github.stage_release(service.repository, contract, "Rehearsal", manifest_path, checksums)
        ledger.require(len(service.uploads) == len(contract.assets) + 1, "GitHub recovery did not preserve exact assets")
        ledger.save(service, ledger.read_release(service, contract.tag), state)
        rows = ledger.bind(state, matrix, manifest_path)
        for row in rows:
            state = ledger.decode(ledger.read_release(service, contract.tag)["body"])
            current = ledger.observe(state["rows"][row["id"]],
                                     ledger.inspector_for(matrix, manifest_path, stage, row))
            state["rows"][row["id"]], upload = ledger.begin(current)
            ledger.require(upload, "new rehearsal file was not eligible for its first upload")
            ledger.save(service, ledger.read_release(service, contract.tag), state)
            markets.upload(row)
            # Drop the in-memory response as if the runner died immediately after acceptance.
            state = ledger.decode(ledger.read_release(service, contract.tag)["body"])
            current = ledger.observe(state["rows"][row["id"]],
                                     ledger.inspector_for(matrix, manifest_path, stage, row))
            _, duplicate = ledger.begin(current)
            ledger.require(not duplicate, "restart authorized a duplicate unindexed upload")
            state["rows"][row["id"]] = ledger.accept(current)
            ledger.save(service, ledger.read_release(service, contract.tag), state)
        for visible, approved in ((False, False), (True, False), (True, True)):
            markets.visible, markets.approved = visible, approved
            state = ledger.decode(ledger.read_release(service, contract.tag)["body"])
            state = ledger.check_all(state, matrix, manifest_path, stage)
            ledger.require(ledger.ready(state) == approved, "moderation was mistaken for publication success")
            ledger.save(service, ledger.read_release(service, contract.tag), state)
        github.publish_release(service.repository, contract, checksums)
        ledger.require(service.release["draft"] is False, "rehearsal did not finalize the complete release")
    return {"tag": contract.tag, "simulated": True, "github_assets": len(service.assets),
            "marketplace_uploads": len(markets.accepted), "duplicate_uploads": 0,
            "interrupted_staging": "recovered", "unindexed_upload": "fenced",
            "moderation": "verified before finalization"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", type=Path, default=Path("build/release"))
    args = parser.parse_args()
    try:
        result = rehearse(load_matrix(ledger.ROOT / "release/release-matrix.json"), args.stage)
        print(json.dumps(result, sort_keys=True))
        return 0
    except (ValueError, RuntimeError, OSError, KeyError, TypeError, subprocess.SubprocessError) as exc:
        parser.exit(1, f"publication rehearsal failed: {exc}\n")


if __name__ == "__main__":
    raise SystemExit(main())
