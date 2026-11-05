#!/usr/bin/env python3
"""Extract an untrusted CI ZIP without following links or accepting zip bombs."""

from __future__ import annotations

import argparse
import json
import os
import shutil
import stat
import sys
import unicodedata
import uuid
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath


MIB = 1024 * 1024


class ArchiveError(ValueError):
    pass


@dataclass(frozen=True)
class ExtractionLimits:
    archive_bytes: int = 256 * MIB
    entries: int = 512
    total_bytes: int = 512 * MIB
    file_bytes: int = 64 * MIB
    compression_ratio: int = 200


def _regular_file_size(path: Path, maximum: int) -> int:
    try:
        metadata = path.lstat()
    except OSError as exc:
        raise ArchiveError(f"cannot inspect archive: {exc}") from exc
    if not stat.S_ISREG(metadata.st_mode):
        raise ArchiveError("archive must be a regular file, not a link or special file")
    if metadata.st_size <= 0 or metadata.st_size > maximum:
        raise ArchiveError(
            f"archive size {metadata.st_size} is outside 1..{maximum} bytes"
        )
    return metadata.st_size


def _safe_name(info: zipfile.ZipInfo) -> tuple[str, bool]:
    raw = info.filename
    if (
        not raw
        or "\\" in raw
        or "\0" in raw
        or any(ord(character) < 32 or ord(character) == 127 for character in raw)
        or unicodedata.normalize("NFC", raw) != raw
    ):
        raise ArchiveError(f"unsafe archive path {raw!r}")
    directory = info.is_dir()
    candidate = raw[:-1] if directory and raw.endswith("/") else raw
    path = PurePosixPath(candidate)
    normalized = path.as_posix()
    if (
        not candidate
        or path.is_absolute()
        or candidate != normalized
        or any(part in {"", ".", ".."} or ":" in part for part in path.parts)
    ):
        raise ArchiveError(f"unsafe archive path {raw!r}")
    return normalized, directory


def _entry_kind(info: zipfile.ZipInfo, directory: bool) -> str:
    unix_mode = info.external_attr >> 16
    file_type = stat.S_IFMT(unix_mode)
    if stat.S_ISLNK(unix_mode):
        raise ArchiveError(f"archive link is forbidden: {info.filename!r}")
    if file_type and not (stat.S_ISDIR(unix_mode) or stat.S_ISREG(unix_mode)):
        raise ArchiveError(f"archive special file is forbidden: {info.filename!r}")
    if directory and file_type == stat.S_IFREG:
        raise ArchiveError(f"directory has regular-file mode: {info.filename!r}")
    if not directory and file_type == stat.S_IFDIR:
        raise ArchiveError(f"file has directory mode: {info.filename!r}")
    return "directory" if directory else "file"


def _inspect(
    archive: zipfile.ZipFile, limits: ExtractionLimits
) -> tuple[list[tuple[zipfile.ZipInfo, str, str]], int]:
    infos = archive.infolist()
    if not infos or len(infos) > limits.entries:
        raise ArchiveError(
            f"archive entry count {len(infos)} is outside 1..{limits.entries}"
        )
    accepted: list[tuple[zipfile.ZipInfo, str, str]] = []
    seen: dict[str, str] = {}
    seen_components: dict[str, str] = {}
    kinds: dict[str, str] = {}
    total = 0
    for info in infos:
        name, directory = _safe_name(info)
        collision_key = name.casefold()
        if collision_key in seen:
            raise ArchiveError(
                f"duplicate or case-colliding archive paths: {seen[collision_key]!r}, {name!r}"
            )
        seen[collision_key] = name
        parts = PurePosixPath(name).parts
        for depth in range(1, len(parts) + 1):
            prefix = PurePosixPath(*parts[:depth]).as_posix()
            prefix_key = prefix.casefold()
            prior = seen_components.get(prefix_key)
            if prior is not None and prior != prefix:
                raise ArchiveError(
                    f"case-colliding archive path components: {prior!r}, {prefix!r}"
                )
            seen_components[prefix_key] = prefix
        kind = _entry_kind(info, directory)
        for parent in PurePosixPath(name).parents:
            parent_name = parent.as_posix()
            if parent_name == ".":
                break
            if kinds.get(parent_name) == "file":
                raise ArchiveError(f"file is also used as a directory: {parent_name!r}")
        if kind == "file" and any(
            existing.startswith(f"{name}/") for existing in kinds
        ):
            raise ArchiveError(f"directory is also used as a file: {name!r}")
        kinds[name] = kind
        if info.flag_bits & 0x1:
            raise ArchiveError(f"encrypted archive entry is forbidden: {name!r}")
        if info.compress_type not in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}:
            raise ArchiveError(f"unsupported compression for archive entry: {name!r}")
        if info.file_size < 0 or info.compress_size < 0:
            raise ArchiveError(f"negative archive entry size: {name!r}")
        if kind == "directory" and info.file_size:
            raise ArchiveError(f"directory carries data: {name!r}")
        if info.file_size > limits.file_bytes:
            raise ArchiveError(f"archive entry is too large: {name!r}")
        total += info.file_size
        if total > limits.total_bytes:
            raise ArchiveError("archive expands beyond the total byte limit")
        if info.file_size and (
            info.compress_size == 0
            or info.file_size > info.compress_size * limits.compression_ratio
        ):
            raise ArchiveError(f"archive entry compression ratio is excessive: {name!r}")
        accepted.append((info, name, kind))
    return accepted, total


def _remove_tree(path: Path) -> None:
    def repair_permissions(function: object, target: str, _error: object) -> None:
        os.chmod(target, stat.S_IRUSR | stat.S_IWUSR | stat.S_IXUSR)
        function(target)  # type: ignore[operator]

    if path.exists():
        shutil.rmtree(path, onerror=repair_permissions)


def extract_bounded_zip(
    archive_path: Path,
    destination: Path,
    limits: ExtractionLimits = ExtractionLimits(),
) -> dict[str, int]:
    _regular_file_size(archive_path, limits.archive_bytes)
    archive_path = archive_path.resolve(strict=True)
    destination_parent = destination.parent.resolve(strict=True)
    if not destination.name or destination.name in {".", ".."}:
        raise ArchiveError("extraction destination must have a safe final name")
    destination = destination_parent / destination.name
    if destination.exists() or destination.is_symlink():
        raise ArchiveError("extraction destination must not already exist")
    temporary = destination_parent / f".{destination.name}.extract-{uuid.uuid4().hex}"
    temporary.mkdir(mode=0o700)
    actual_total = 0
    file_count = 0
    try:
        with zipfile.ZipFile(archive_path, "r") as archive:
            accepted, declared_total = _inspect(archive, limits)
            for info, name, kind in accepted:
                output = temporary.joinpath(*PurePosixPath(name).parts)
                if kind == "directory":
                    output.mkdir(mode=0o700, parents=True, exist_ok=True)
                    if not stat.S_ISDIR(output.lstat().st_mode):
                        raise ArchiveError(
                            f"archive directory collided with a non-directory: {name!r}"
                        )
                    continue
                output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
                flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
                if hasattr(os, "O_NOFOLLOW"):
                    flags |= os.O_NOFOLLOW
                descriptor = os.open(output, flags, 0o600)
                written = 0
                try:
                    with archive.open(info, "r") as source, os.fdopen(
                        descriptor, "wb", closefd=True
                    ) as sink:
                        descriptor = -1
                        while chunk := source.read(1024 * 1024):
                            written += len(chunk)
                            actual_total += len(chunk)
                            if (
                                written > limits.file_bytes
                                or actual_total > limits.total_bytes
                            ):
                                raise ArchiveError("archive exceeded byte limits while extracting")
                            sink.write(chunk)
                finally:
                    if descriptor >= 0:
                        os.close(descriptor)
                if written != info.file_size:
                    raise ArchiveError(f"archive entry size changed while reading: {name!r}")
                file_count += 1
            if actual_total != declared_total:
                raise ArchiveError("archive expanded size does not match its directory")
        os.replace(temporary, destination)
    except Exception as exc:
        # Corrupt entries surface as zlib.error, EOFError and other decoder
        # errors besides BadZipFile; none may leave the untrusted partial tree.
        _remove_tree(temporary)
        if isinstance(exc, ArchiveError):
            raise
        raise ArchiveError(
            f"cannot safely extract archive: {type(exc).__name__}: {exc}"
        ) from exc
    except BaseException:
        _remove_tree(temporary)
        raise
    return {"entries": len(accepted), "files": file_count, "bytes": actual_total}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("destination", type=Path)
    parser.add_argument("--max-archive-bytes", type=int, default=ExtractionLimits.archive_bytes)
    parser.add_argument("--max-entries", type=int, default=ExtractionLimits.entries)
    parser.add_argument("--max-total-bytes", type=int, default=ExtractionLimits.total_bytes)
    parser.add_argument("--max-file-bytes", type=int, default=ExtractionLimits.file_bytes)
    parser.add_argument(
        "--max-compression-ratio",
        type=int,
        default=ExtractionLimits.compression_ratio,
    )
    args = parser.parse_args(argv)
    try:
        raw_limits = (
            args.max_archive_bytes,
            args.max_entries,
            args.max_total_bytes,
            args.max_file_bytes,
            args.max_compression_ratio,
        )
        if any(value <= 0 for value in raw_limits):
            raise ArchiveError("all extraction limits must be positive integers")
        summary = extract_bounded_zip(
            args.archive,
            args.destination,
            ExtractionLimits(*raw_limits),
        )
    except ArchiveError as exc:
        parser.error(str(exc))
    print(json.dumps(summary, sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    sys.exit(main())
