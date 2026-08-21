#!/usr/bin/env python3
"""Build the Quick Skin landing page and multi-version E2E gallery."""

from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
from pathlib import Path
from typing import Any
from urllib.parse import urlparse


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "e2e"))
sys.path.insert(0, str(REPO / "scripts" / "pages"))
sys.path.insert(0, str(REPO / "scripts" / "release"))

from evidence import (  # noqa: E402
    COMPACT_SCHEMA_VERSION,
    PublicEvidenceError,
    sha256_file,
    validate_bundle,
)
from packaged_runtime import (  # noqa: E402
    RuntimeFailure,
    compare_screenshots,
    inspect_screenshot,
)
from version_branches import parse_version_branch  # noqa: E402


SITE_SOURCE = REPO / "site"
DEFAULT_MATRIX = REPO / "release" / "release-matrix.json"
MAX_SITE_BYTES = 1024 * 1024 * 1024


class SiteBuildError(ValueError):
    pass


def version_key(version: str) -> tuple[int, ...]:
    try:
        return tuple(int(part) for part in version.split("."))
    except ValueError as exc:
        raise SiteBuildError(f"invalid numeric Minecraft version {version!r}") from exc


def loader_name(loader: str) -> str:
    return {"fabric": "Fabric", "forge": "Forge", "neoforge": "NeoForge"}.get(
        loader, loader.title()
    )


def https_url(value: str, label: str) -> str:
    if value != value.strip() or any(character.isspace() for character in value):
        raise SiteBuildError(f"{label} must not contain whitespace or control characters")
    try:
        parsed = urlparse(value)
        port = parsed.port
    except ValueError as exc:
        raise SiteBuildError(f"{label} is not a valid HTTPS URL: {exc}") from exc
    if (
        parsed.scheme != "https"
        or not parsed.netloc
        or parsed.username is not None
        or parsed.password is not None
        or port is not None and not 1 <= port <= 65535
    ):
        raise SiteBuildError(f"{label} must be an absolute HTTPS URL without credentials")
    return value


def load_project(path: Path, repository: str) -> dict[str, Any]:
    try:
        matrix = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SiteBuildError(f"cannot read project metadata from {path}: {exc}") from exc
    project = matrix.get("project") if isinstance(matrix, dict) else None
    if not isinstance(project, dict):
        raise SiteBuildError("release matrix project metadata is missing")
    required = ("name", "description", "homepage", "sources", "issues", "license")
    if any(not isinstance(project.get(key), str) or not project[key] for key in required):
        raise SiteBuildError("release matrix project metadata is incomplete")
    homepage = https_url(project["homepage"], "project.homepage")
    sources = https_url(project["sources"], "project.sources")
    issues = https_url(project["issues"], "project.issues")
    repository_url = f"https://github.com/{repository}"
    if sources.rstrip("/") != repository_url or issues.rstrip("/") != repository_url + "/issues":
        raise SiteBuildError("release matrix source and issue URLs disagree with the repository")
    return {
        "name": project["name"],
        "description": project["description"],
        "license": project["license"],
        "links": [
            {
                "id": "modrinth",
                "title": "Modrinth",
                "description": "Install releases and follow updates.",
                "url": homepage,
            },
            {
                "id": "curseforge",
                "title": "CurseForge",
                "description": "Download Quick Skin from CurseForge.",
                "url": "https://www.curseforge.com/minecraft/mc-mods/quick-skin",
            },
            {
                "id": "github",
                "title": "GitHub",
                "description": "Source, releases and issue tracking.",
                "url": repository_url,
            },
        ],
        "issues": issues,
    }


def optimize_image(source: Path, destination: Path) -> tuple[int, int]:
    try:
        from PIL import Image
    except ImportError as exc:  # pragma: no cover - Pages installs the locked wheel
        raise SiteBuildError(
            "Pillow is required for the default WebP build; use --copy-images only for tests"
        ) from exc
    try:
        with Image.open(source) as image:
            image.load()
            rendered = image.convert("RGB")
            rendered.thumbnail((1600, 900), Image.Resampling.LANCZOS)
            destination.parent.mkdir(parents=True, exist_ok=True)
            rendered.save(destination, "WEBP", quality=82, method=6, exact=True)
            return rendered.size
    except (OSError, ValueError) as exc:
        raise SiteBuildError(f"cannot optimize public screenshot {source}: {exc}") from exc


def copy_image(source: Path, destination: Path, frame: dict[str, Any]) -> tuple[int, int]:
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
    width = frame.get("width")
    height = frame.get("height")
    if not isinstance(width, int) or not isinstance(height, int):
        raise SiteBuildError("public frame dimensions are invalid")
    return width, height


def published_digest(path: Path) -> str:
    try:
        return sha256_file(path)
    except ValueError as exc:
        raise SiteBuildError(f"cannot hash published gallery image {path}: {exc}") from exc


def build(
    *,
    evidence_root: Path,
    output: Path,
    repository: str,
    matrix_path: Path = DEFAULT_MATRIX,
    optimize: bool = True,
    require_compact: bool = False,
    expected_branches: set[str] | None = None,
) -> dict[str, Any]:
    root = evidence_root.resolve()
    if not root.is_dir():
        raise SiteBuildError(f"evidence root does not exist: {root}")
    if output.exists():
        raise SiteBuildError(f"refusing to replace existing site output {output}")
    if not SITE_SOURCE.is_dir():
        raise SiteBuildError(f"static site source does not exist: {SITE_SOURCE}")

    root_entries = sorted(root.iterdir())
    if any(not path.is_dir() for path in root_entries):
        raise SiteBuildError("evidence root may contain only release-branch directories")
    candidate_names = {path.name for path in root_entries}
    if expected_branches is not None and candidate_names != expected_branches:
        raise SiteBuildError(
            "evidence root disagrees with the discovered release branches: "
            f"missing={sorted(expected_branches - candidate_names)}, "
            f"extra={sorted(candidate_names - expected_branches)}"
        )
    manifests: list[dict[str, Any]] = []
    for candidate in root_entries:
        parsed = parse_version_branch(candidate.name)
        if parsed is None:
            raise SiteBuildError(f"unexpected directory in evidence root: {candidate.name}")
        try:
            manifests.append(
                validate_bundle(
                    root,
                    candidate.name,
                    expected_kind="compact" if require_compact else None,
                    expected_repository=repository,
                )
            )
        except PublicEvidenceError as exc:
            raise SiteBuildError(str(exc)) from exc
    if not manifests:
        raise SiteBuildError("cannot build a public site without release evidence")
    versions = [manifest["release"]["version"] for manifest in manifests]
    if len(set(versions)) != len(versions):
        raise SiteBuildError(f"more than one public bundle claims a Minecraft version: {versions}")
    manifests.sort(key=lambda item: version_key(item["release"]["version"]), reverse=True)
    release_rank = {
        manifest["release"]["version"]: index for index, manifest in enumerate(manifests)
    }

    shutil.copytree(SITE_SOURCE, output)
    (output / ".nojekyll").write_text("", encoding="utf-8")
    shutil.copyfile(REPO / "icon.png", output / "assets" / "icon.png")

    gallery_frames: list[dict[str, Any]] = []
    gallery_lanes: list[dict[str, Any]] = []
    gallery_comparisons: list[dict[str, Any]] = []
    release_rows: list[dict[str, Any]] = []
    frame_ids: set[str] = set()
    rendered_assets: dict[
        tuple[str, str, str], tuple[Path, int, int, str, dict[str, Any]]
    ] = {}
    inspected_sources: dict[Path, dict[str, Any]] = {}
    for manifest in manifests:
        compact = manifest["schema_version"] == COMPACT_SCHEMA_VERSION
        release = manifest["release"]
        branch = release["branch"]
        provenance = manifest["provenance"]
        loaders = sorted(
            {artifact["loader"] for artifact in release["artifacts"]},
            key=lambda item: loader_name(item),
        )
        release_frames = manifest["frames"]
        release_rows.append(
            {
                "version": release["version"],
                "loaders": loaders,
                "loader_names": [loader_name(loader) for loader in loaders],
                "frame_count": len(release_frames),
                "lane_count": len(manifest["lanes"]),
                "scenarios": list(release["scenarios"]),
                "contract_sha256": manifest["contract_sha256"],
                "contract_url": (
                    f"https://github.com/{repository}/blob/"
                    f"{provenance['target']['sha']}/e2e/scenario-contract.json"
                ),
                "source_branch": provenance["source"]["branch"],
                "source_sha": provenance["source"]["sha"],
                "source_created_at": provenance["source"]["created_at"],
                "source_run_url": provenance["source"]["run_url"],
                "target_branch": provenance["target"]["branch"],
                "target_sha": provenance["target"]["sha"],
                "target_created_at": provenance["target"]["created_at"],
                "short_sha": provenance["target"]["sha"][:12],
                "target_run_url": provenance["target"]["run_url"],
                "branch_url": f"https://github.com/{repository}/tree/{branch}",
            }
        )
        for lane in manifest["lanes"]:
            gallery_lanes.append(
                {
                    key: lane[key]
                    for key in (
                        "lane_id",
                        "artifact_node",
                        "version",
                        "loader",
                        "scenario",
                        "jar_sha256",
                        "status",
                        "roles",
                        "elapsed_s",
                    )
                }
                | {"loader_name": loader_name(lane["loader"])}
            )
        release_source_paths: dict[str, Path] = {}
        for frame in release_frames:
            frame_id = frame["frame_id"]
            if frame_id in frame_ids:
                raise SiteBuildError(f"duplicate frame identity across release bundles: {frame_id}")
            frame_ids.add(frame_id)
            derivative = frame.get("derivative") if compact else None
            source = root / branch / (
                derivative["asset"] if derivative is not None else frame["asset"]
            )
            release_source_paths[frame_id] = source
            if optimize or compact:
                if source not in inspected_sources:
                    try:
                        inspected_sources[source] = inspect_screenshot(
                            source,
                            expected_format="WEBP" if compact else "PNG",
                        )
                    except RuntimeFailure as exc:
                        raise SiteBuildError(str(exc)) from exc
                expected_pixel_validation = (
                    derivative["pixel_validation"]
                    if derivative is not None
                    else frame["pixel_validation"]
                )
                if inspected_sources[source] != expected_pixel_validation:
                    raise SiteBuildError(
                        f"protected pixel reinspection disagrees for {frame_id}"
                    )
            extension = "webp" if compact or optimize else "png"
            render_digest = (
                derivative["file_sha256"]
                if derivative is not None
                else frame["file_sha256"]
            )
            render_key = (branch, render_digest, extension)
            if render_key in rendered_assets:
                (
                    image_relative,
                    rendered_width,
                    rendered_height,
                    rendered_sha256,
                    rendered_metrics,
                ) = rendered_assets[render_key]
            else:
                staged_relative = Path("e2e") / "images" / branch / (
                    frame["file_sha256"] + ".rendering." + extension
                )
                staged = output / staged_relative
                if derivative is not None:
                    rendered_width, rendered_height = copy_image(
                        source, staged, derivative
                    )
                    rendered_metrics = derivative["pixel_validation"]
                elif optimize:
                    rendered_width, rendered_height = optimize_image(source, staged)
                    try:
                        rendered_metrics = inspect_screenshot(
                            staged, expected_format="WEBP"
                        )
                    except RuntimeFailure as exc:
                        raise SiteBuildError(str(exc)) from exc
                else:
                    rendered_width, rendered_height = copy_image(source, staged, frame)
                    rendered_metrics = frame["pixel_validation"]
                rendered_sha256 = published_digest(staged)
                if (
                    rendered_metrics["file_sha256"] != rendered_sha256
                    or rendered_metrics["width"] != rendered_width
                    or rendered_metrics["height"] != rendered_height
                ):
                    raise SiteBuildError(
                        f"published image metrics disagree with its bytes for {frame_id}"
                    )
                image_relative = Path("e2e") / "images" / branch / (
                    rendered_sha256 + "." + extension
                )
                destination = output / image_relative
                if destination != staged:
                    if destination.exists():
                        if published_digest(destination) != rendered_sha256:
                            raise SiteBuildError(
                                f"published image digest collision at {destination}"
                            )
                        staged.unlink()
                    else:
                        staged.rename(destination)
                rendered_assets[render_key] = (
                    image_relative,
                    rendered_width,
                    rendered_height,
                    rendered_sha256,
                    rendered_metrics,
                )
            public_frame = {
                key: value
                for key, value in frame.items()
                if key
                in {
                    "frame_id",
                    "capture_id",
                    "capture_order",
                    "title",
                    "expectation",
                    "runtime_evidence",
                    "review_tier",
                    "artifact_node",
                    "version",
                    "loader",
                    "scenario",
                    "role",
                    "step",
                }
            }
            public_frame.update(
                {
                    "lane_id": f"{frame['artifact_node']}/{frame['scenario']}",
                    "loader_name": loader_name(frame["loader"]),
                    "image": image_relative.relative_to("e2e").as_posix(),
                    "width": rendered_width,
                    "height": rendered_height,
                    "source_width": frame["width"],
                    "source_height": frame["height"],
                    "source_file_sha256": frame["file_sha256"],
                    "source_pixel_validation": frame["pixel_validation"],
                    "published_file_sha256": rendered_sha256,
                    "published_format": extension,
                    "published_pixel_validation": rendered_metrics,
                    "alt": (
                        f"{frame['title']} in Minecraft {frame['version']} on "
                        f"{loader_name(frame['loader'])}, {frame['role'].replace('_', ' ')}. "
                        f"Expected view: {frame['expectation']}"
                    ),
                    "source_run_url": provenance["source"]["run_url"],
                    "source_branch": provenance["source"]["branch"],
                    "source_sha": provenance["source"]["sha"],
                    "source_created_at": provenance["source"]["created_at"],
                    "target_run_url": provenance["target"]["run_url"],
                    "target_branch": provenance["target"]["branch"],
                    "target_sha": provenance["target"]["sha"],
                    "target_created_at": provenance["target"]["created_at"],
                }
            )
            gallery_frames.append(public_frame)
        for comparison in manifest["comparisons"]:
            metrics = comparison["pixel_validation"]
            rendered_metrics = (
                comparison["derivative_pixel_validation"] if compact else metrics
            )
            if optimize or compact:
                region = rendered_metrics.get("region")
                try:
                    reinspected = compare_screenshots(
                        release_source_paths[comparison["first_frame_id"]],
                        release_source_paths[comparison["second_frame_id"]],
                        rendered_metrics["required_changed_fraction"],
                        tuple(region) if region is not None else None,
                    )
                except RuntimeFailure as exc:
                    raise SiteBuildError(str(exc)) from exc
                if reinspected != rendered_metrics:
                    raise SiteBuildError(
                        "protected comparison reinspection disagrees for "
                        f"{comparison['comparison_id']}"
                    )
            gallery_comparisons.append(
                {
                    key: comparison[key]
                    for key in (
                        "comparison_id",
                        "artifact_node",
                        "version",
                        "loader",
                        "scenario",
                        "role",
                        "first_frame_id",
                        "second_frame_id",
                    )
                }
                | {"source_pixel_validation": metrics}
                | (
                    {"published_pixel_validation": rendered_metrics}
                    if compact
                    else {}
                )
            )

    gallery_frames.sort(
        key=lambda item: (
            release_rank[item["version"]],
            item["loader_name"],
            item["capture_order"],
        )
    )
    gallery_lanes.sort(key=lambda item: item["lane_id"])
    gallery_comparisons.sort(key=lambda item: item["comparison_id"])
    project = load_project(matrix_path, repository)
    site_data = {
        "schema_version": 1,
        "project": project,
        "releases": release_rows,
        "gallery_url": "e2e/",
        "repository_url": f"https://github.com/{repository}",
    }
    gallery_data = {
        "schema_version": 2,
        "project": {"name": project["name"], "repository_url": site_data["repository_url"]},
        "releases": release_rows,
        "lanes": gallery_lanes,
        "frames": gallery_frames,
        "comparisons": gallery_comparisons,
    }
    (output / "site-data.json").write_text(
        json.dumps(site_data, indent=2, sort_keys=True, allow_nan=False) + "\n",
        encoding="utf-8",
    )
    (output / "e2e" / "gallery-data.json").write_text(
        json.dumps(gallery_data, indent=2, sort_keys=True, allow_nan=False) + "\n",
        encoding="utf-8",
    )
    total_size = sum(path.stat().st_size for path in output.rglob("*") if path.is_file())
    if total_size > MAX_SITE_BYTES:
        raise SiteBuildError(f"generated Pages site exceeds 1 GiB: {total_size} bytes")
    return {
        "versions": len(release_rows),
        "frames": len(gallery_frames),
        "bytes": total_size,
        "output": str(output),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--matrix", type=Path, default=DEFAULT_MATRIX)
    parser.add_argument(
        "--expected-branches-json",
        help="exact JSON array of release branches discovered by the protected workflow",
    )
    parser.add_argument(
        "--copy-images",
        action="store_true",
        help="copy raw PNG fixtures instead of optimizing them (compact WebP is always copied)",
    )
    parser.add_argument(
        "--require-compact-evidence",
        action="store_true",
        help="reject raw PNG handoffs; protected Pages fan-in must use compact caches",
    )
    args = parser.parse_args(argv)
    expected_branches: set[str] | None = None
    if args.expected_branches_json is not None:
        try:
            raw_branches = json.loads(args.expected_branches_json)
        except json.JSONDecodeError as exc:
            parser.error(f"--expected-branches-json is invalid JSON: {exc}")
        if (
            not isinstance(raw_branches, list)
            or not raw_branches
            or any(
                not isinstance(branch, str) or parse_version_branch(branch) is None
                for branch in raw_branches
            )
            or len(set(raw_branches)) != len(raw_branches)
        ):
            parser.error("--expected-branches-json must be unique release branch names")
        expected_branches = set(raw_branches)
    try:
        summary = build(
            evidence_root=args.evidence_root,
            output=args.output,
            repository=args.repository,
            matrix_path=args.matrix,
            optimize=not args.copy_images,
            require_compact=args.require_compact_evidence,
            expected_branches=expected_branches,
        )
    except (SiteBuildError, PublicEvidenceError) as exc:
        print(f"site build error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
