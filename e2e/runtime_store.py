"""Content-addressed storage for packaged Minecraft runtime installations.

The store is deliberately independent from :mod:`packaged_runtime`.  A future
integration has three explicit steps::

    recipe = RuntimeRecipe.for_host(...)
    workspace = RunWorkspace.create(output_root)
    with store.get_or_create_lease(recipe, install_into) as stored:
        store.materialize(stored, workspace.path / "client")

``install_into`` receives a new, empty staging directory.  Store objects are
immutable; ``materialize`` always copies them into a caller-owned directory so
Minecraft can mutate its runtime without mutating cached blobs.

The on-disk namespace is ``<cache>/RuntimeStore/v1``.  Recipe records point to
canonical tree manifests, manifests point to SHA-256 blobs, and active leases
protect all three layers from garbage collection.  Recipe locks are both
in-process and operating-system file locks, so two scenarios or processes do
not publish the same installation concurrently.
"""

from __future__ import annotations

import errno
import hashlib
import json
import math
import os
import platform
import re
import shutil
import stat
import tempfile
import threading
import time
import uuid
import warnings
import weakref
from collections.abc import Callable, Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, BinaryIO


STORE_DIRECTORY = "RuntimeStore"
STORE_VERSION = "v1"
RECIPE_SCHEMA = 2
RECIPE_ROLES = frozenset({"client", "server"})
TREE_SCHEMA = 1
RECIPE_RECORD_SCHEMA = 1
LEASE_SCHEMA = 1

_DIGEST_RE = re.compile(r"[0-9a-f]{64}")
_LEASE_ID_RE = re.compile(r"[0-9a-f]{32}")
_LEASE_ENTRY_RE = re.compile(r"([0-9a-f]{32})\.(json|lock)")
_BUILD_STAGING_RE = re.compile(r"build-([0-9a-f]{64})-[A-Za-z0-9_]+")
_TRASH_ENTRY_RE = re.compile(r"gc-(recipe|tree|blob|build)-([0-9a-f]{32})")
_MAX_RECIPE_RECORD_BYTES = 64 * 1024
_MAX_MANIFEST_BYTES = 64 * 1024 * 1024
_MAX_LEASE_BYTES = 64 * 1024 * 1024
_MAX_WORKSPACE_MARKER_BYTES = 4 * 1024
_COPY_CHUNK_SIZE = 1024 * 1024
_WORKSPACE_PREFIX_RE = re.compile(r"[A-Za-z0-9_.-]+")
_WORKSPACE_SNAPSHOT_MARKER = ".quickskin-run-workspace.json"
_WORKSPACE_SNAPSHOT_SCHEMA = 1
_WORKSPACE_SNAPSHOT_KIND = "quickskin-run-workspace-snapshot"


def _is_reparse_point(path_stat: os.stat_result) -> bool:
    attribute = getattr(stat, "FILE_ATTRIBUTE_REPARSE_POINT", 0)
    return bool(attribute and getattr(path_stat, "st_file_attributes", 0) & attribute)


def _is_link_or_reparse(path_stat: os.stat_result) -> bool:
    return stat.S_ISLNK(path_stat.st_mode) or _is_reparse_point(path_stat)


def _same_identity(first: os.stat_result, second: os.stat_result) -> bool:
    return first.st_dev == second.st_dev and first.st_ino == second.st_ino


def _read_bounded_regular_file(path: Path, maximum: int, label: str) -> bytes:
    """Read one stable regular-file inode without following path substitutions."""

    try:
        initial = path.lstat()
    except FileNotFoundError as exc:
        raise StoreCorruptionError(f"{label} is missing: {path}") from exc
    except OSError as exc:
        raise StoreCorruptionError(f"could not inspect {label} {path}: {exc}") from exc
    if _is_link_or_reparse(initial) or not stat.S_ISREG(initial.st_mode):
        raise StoreCorruptionError(f"{label} is not a regular file: {path}")
    if initial.st_size > maximum:
        raise StoreCorruptionError(f"{label} exceeds its size limit: {path}")

    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
    flags |= getattr(os, "O_NOFOLLOW", 0)
    flags |= getattr(os, "O_BINARY", 0)
    descriptor: int | None = None
    try:
        descriptor = os.open(path, flags)
        opened = os.fstat(descriptor)
        current = path.lstat()
        if (
            _is_link_or_reparse(opened)
            or _is_link_or_reparse(current)
            or not stat.S_ISREG(opened.st_mode)
            or not stat.S_ISREG(current.st_mode)
            or not _same_identity(initial, opened)
            or not _same_identity(opened, current)
        ):
            raise StoreCorruptionError(f"{label} changed while it was opened: {path}")
        chunks: list[bytes] = []
        remaining = maximum + 1
        while remaining:
            chunk = os.read(descriptor, min(_COPY_CHUNK_SIZE, remaining))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        raw = b"".join(chunks)
        final_opened = os.fstat(descriptor)
        final_path = path.lstat()
        if (
            len(raw) > maximum
            or len(raw) != initial.st_size
            or final_opened.st_size != initial.st_size
            or not _same_identity(opened, final_opened)
            or not _same_identity(opened, final_path)
            or _is_link_or_reparse(final_path)
        ):
            raise StoreCorruptionError(f"{label} changed while it was read: {path}")
        return raw
    except StoreCorruptionError:
        raise
    except OSError as exc:
        raise StoreCorruptionError(f"could not safely read {label} {path}: {exc}") from exc
    finally:
        if descriptor is not None:
            os.close(descriptor)


def _make_writable(path: Path) -> None:
    path_stat = path.lstat()
    if _is_link_or_reparse(path_stat):
        raise StoreCorruptionError(f"refusing to change permissions through link: {path}")
    os.chmod(path, stat.S_IMODE(path_stat.st_mode) | stat.S_IWUSR)


def _remove_tree(path: Path, expected: os.stat_result | None = None) -> None:
    """Remove a quarantined real directory, retrying read-only children."""

    path_stat = path.lstat()
    if (
        _is_link_or_reparse(path_stat)
        or not stat.S_ISDIR(path_stat.st_mode)
        or (expected is not None and not _same_identity(path_stat, expected))
    ):
        raise StoreCorruptionError(f"refusing to remove unsafe directory: {path}")

    def make_writable_and_retry(
        operation: Callable[[str], None], name: str, _error: object
    ) -> None:
        candidate = Path(name)
        _make_writable(candidate)
        operation(name)

    shutil.rmtree(path, onerror=make_writable_and_retry)


class RuntimeStoreError(RuntimeError):
    """Base error for the runtime store."""


class RecipeNotFoundError(RuntimeStoreError):
    """Raised when a requested recipe has not been published."""


class StoreCorruptionError(RuntimeStoreError):
    """Raised when a store record or content-addressed object is invalid."""


class InvalidRuntimeTreeError(RuntimeStoreError):
    """Raised when a source or materialized runtime tree is unsafe."""


class LockTimeoutError(RuntimeStoreError):
    """Raised when an optional lock timeout expires."""


def canonical_json_bytes(value: Any) -> bytes:
    """Return the unique JSON encoding used for content identities."""

    try:
        encoded = json.dumps(
            value,
            ensure_ascii=False,
            allow_nan=False,
            separators=(",", ":"),
            sort_keys=True,
        )
    except (TypeError, ValueError) as exc:
        raise RuntimeStoreError(f"value cannot be encoded as canonical JSON: {exc}") from exc
    return encoded.encode("utf-8")


def _json_file_bytes(value: Any) -> bytes:
    return canonical_json_bytes(value) + b"\n"


def _digest_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _require_digest(value: object, label: str) -> str:
    if not isinstance(value, str) or _DIGEST_RE.fullmatch(value) is None:
        raise StoreCorruptionError(f"{label} must be a lowercase SHA-256 digest")
    return value


def _require_nonempty_string(value: object, label: str) -> str:
    if not isinstance(value, str) or not value or value != value.strip():
        raise RuntimeStoreError(f"{label} must be a non-empty, trimmed string")
    if len(value) > 512 or any(ord(character) < 0x20 for character in value):
        raise RuntimeStoreError(f"{label} contains unsupported characters")
    return value


def _require_exact_keys(payload: Mapping[str, Any], expected: set[str], label: str) -> None:
    actual = set(payload)
    if actual != expected:
        missing = sorted(expected - actual)
        extra = sorted(actual - expected)
        raise StoreCorruptionError(
            f"{label} keys are invalid (missing={missing}, extra={extra})"
        )


def _require_timestamp(value: object, label: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise StoreCorruptionError(f"{label} must be a finite non-negative timestamp")
    timestamp = float(value)
    if not math.isfinite(timestamp) or timestamp < 0:
        raise StoreCorruptionError(f"{label} must be a finite non-negative timestamp")
    return timestamp


@dataclass(frozen=True)
class RuntimeRecipe:
    """Every input that may change an installed Minecraft client or server tree.

    `role` keeps the two installed trees in separate identity namespaces: the same Minecraft
    version, loader and installer produce different content for a client and for a server.
    """

    os_name: str
    architecture: str
    java_major: int
    minecraft_version: str
    loader: str
    loader_version: str
    installer_sha256: str
    launcher_library_revision: str
    normalizer_revision: str
    role: str
    schema: int = RECIPE_SCHEMA

    def __post_init__(self) -> None:
        if (
            not isinstance(self.schema, int)
            or isinstance(self.schema, bool)
            or self.schema != RECIPE_SCHEMA
        ):
            raise RuntimeStoreError(f"recipe schema must be exactly {RECIPE_SCHEMA}")
        for field_name in (
            "os_name",
            "architecture",
            "minecraft_version",
            "loader",
            "loader_version",
            "launcher_library_revision",
            "normalizer_revision",
            "role",
        ):
            _require_nonempty_string(getattr(self, field_name), f"recipe {field_name}")
        if self.role not in RECIPE_ROLES:
            raise RuntimeStoreError(
                f"recipe role must be one of {sorted(RECIPE_ROLES)}"
            )
        if isinstance(self.java_major, bool) or not isinstance(self.java_major, int):
            raise RuntimeStoreError("recipe java_major must be a positive integer")
        if self.java_major <= 0:
            raise RuntimeStoreError("recipe java_major must be a positive integer")
        try:
            _require_digest(self.installer_sha256, "recipe installer_sha256")
        except StoreCorruptionError as exc:
            raise RuntimeStoreError(str(exc)) from exc

    @classmethod
    def for_host(
        cls,
        *,
        java_major: int,
        minecraft_version: str,
        loader: str,
        loader_version: str,
        installer_sha256: str,
        launcher_library_revision: str,
        normalizer_revision: str,
        role: str,
        os_name: str | None = None,
        architecture: str | None = None,
    ) -> RuntimeRecipe:
        """Build a recipe with explicit host OS and architecture identity."""

        return cls(
            os_name=os_name if os_name is not None else platform.system().lower(),
            architecture=(
                architecture if architecture is not None else platform.machine().lower()
            ),
            java_major=java_major,
            minecraft_version=minecraft_version,
            loader=loader,
            loader_version=loader_version,
            installer_sha256=installer_sha256,
            launcher_library_revision=launcher_library_revision,
            normalizer_revision=normalizer_revision,
            role=role,
        )

    @classmethod
    def from_payload(cls, payload: object) -> RuntimeRecipe:
        if not isinstance(payload, dict):
            raise StoreCorruptionError("recipe payload must be an object")
        _require_exact_keys(
            payload,
            {
                "schema",
                "os",
                "arch",
                "java",
                "minecraft",
                "loader",
                "loader_version",
                "installer_sha256",
                "launcher_library_revision",
                "normalizer_revision",
                "role",
            },
            "recipe",
        )
        try:
            return cls(
                schema=payload["schema"],
                os_name=payload["os"],
                architecture=payload["arch"],
                java_major=payload["java"],
                minecraft_version=payload["minecraft"],
                loader=payload["loader"],
                loader_version=payload["loader_version"],
                installer_sha256=payload["installer_sha256"],
                launcher_library_revision=payload["launcher_library_revision"],
                normalizer_revision=payload["normalizer_revision"],
                role=payload["role"],
            )
        except RuntimeStoreError as exc:
            raise StoreCorruptionError(f"invalid recipe payload: {exc}") from exc

    def payload(self) -> dict[str, Any]:
        return {
            "schema": self.schema,
            "os": self.os_name,
            "arch": self.architecture,
            "java": self.java_major,
            "minecraft": self.minecraft_version,
            "loader": self.loader,
            "loader_version": self.loader_version,
            "installer_sha256": self.installer_sha256,
            "launcher_library_revision": self.launcher_library_revision,
            "normalizer_revision": self.normalizer_revision,
            "role": self.role,
        }

    def digest(self) -> str:
        return _digest_bytes(canonical_json_bytes(self.payload()))


def _validate_manifest_path(value: object) -> str:
    if (
        not isinstance(value, str)
        or not value
        or "\\" in value
        or ":" in value
        or any(ord(character) < 0x20 for character in value)
    ):
        raise StoreCorruptionError("tree entry path is invalid")
    path = PurePosixPath(value)
    if path.is_absolute() or path.as_posix() != value:
        raise StoreCorruptionError(f"tree entry path is not canonical: {value!r}")
    if any(part in ("", ".", "..") for part in path.parts):
        raise StoreCorruptionError(f"tree entry path escapes its root: {value!r}")
    return value


@dataclass(frozen=True)
class TreeEntry:
    path: str
    size: int
    sha256: str
    mode: int

    def __post_init__(self) -> None:
        _validate_manifest_path(self.path)
        if isinstance(self.size, bool) or not isinstance(self.size, int) or self.size < 0:
            raise StoreCorruptionError(f"invalid size for tree entry {self.path!r}")
        _require_digest(self.sha256, f"tree entry {self.path!r} sha256")
        if isinstance(self.mode, bool) or not isinstance(self.mode, int):
            raise StoreCorruptionError(f"invalid mode for tree entry {self.path!r}")
        if not 0 <= self.mode <= 0o777:
            raise StoreCorruptionError(f"invalid mode for tree entry {self.path!r}")

    @classmethod
    def from_payload(cls, payload: object) -> TreeEntry:
        if not isinstance(payload, dict):
            raise StoreCorruptionError("tree entry must be an object")
        _require_exact_keys(payload, {"path", "size", "sha256", "mode"}, "tree entry")
        return cls(
            path=payload["path"],
            size=payload["size"],
            sha256=payload["sha256"],
            mode=payload["mode"],
        )

    def payload(self) -> dict[str, Any]:
        return {
            "path": self.path,
            "size": self.size,
            "sha256": self.sha256,
            "mode": self.mode,
        }


@dataclass(frozen=True)
class TreeManifest:
    entries: tuple[TreeEntry, ...]
    schema: int = TREE_SCHEMA

    def __post_init__(self) -> None:
        if (
            not isinstance(self.schema, int)
            or isinstance(self.schema, bool)
            or self.schema != TREE_SCHEMA
        ):
            raise StoreCorruptionError(f"tree schema must be exactly {TREE_SCHEMA}")
        paths = [entry.path for entry in self.entries]
        if paths != sorted(paths) or len(paths) != len(set(paths)):
            raise StoreCorruptionError("tree entries must have unique, sorted paths")
        blob_sizes: dict[str, int] = {}
        for entry in self.entries:
            previous = blob_sizes.setdefault(entry.sha256, entry.size)
            if previous != entry.size:
                raise StoreCorruptionError(
                    f"tree blob {entry.sha256} has contradictory sizes"
                )

    @classmethod
    def from_payload(cls, payload: object) -> TreeManifest:
        if not isinstance(payload, dict):
            raise StoreCorruptionError("tree manifest must be an object")
        _require_exact_keys(payload, {"schema", "files"}, "tree manifest")
        files = payload["files"]
        if not isinstance(files, list):
            raise StoreCorruptionError("tree manifest files must be an array")
        return cls(
            schema=payload["schema"],
            entries=tuple(TreeEntry.from_payload(entry) for entry in files),
        )

    def payload(self) -> dict[str, Any]:
        return {
            "schema": self.schema,
            "files": [entry.payload() for entry in self.entries],
        }

    def digest(self) -> str:
        return _digest_bytes(canonical_json_bytes(self.payload()))

    @property
    def blob_digests(self) -> tuple[str, ...]:
        return tuple(sorted({entry.sha256 for entry in self.entries}))

    @property
    def logical_size_bytes(self) -> int:
        return sum(entry.size for entry in self.entries)


@dataclass(frozen=True)
class StoredRuntime:
    recipe: RuntimeRecipe
    recipe_digest: str
    tree_digest: str
    manifest: TreeManifest
    created_at: float
    last_accessed_at: float
    manifest_path: Path


@dataclass(frozen=True)
class StoreMetrics:
    """In-memory cache metrics; ``pruned`` counts removed recipe entries."""

    hits: int = 0
    misses: int = 0
    pruned: int = 0
    pruned_trees: int = 0
    pruned_blobs: int = 0
    pruned_bytes: int = 0


@dataclass(frozen=True)
class GcResult:
    pruned: int
    pruned_trees: int
    pruned_blobs: int
    pruned_bytes: int
    retained_bytes: int


@dataclass(frozen=True)
class WorkspacePromotion:
    current: Path
    replaced: bool
    recovered_interrupted: bool


class RunWorkspace:
    """A newly allocated run directory that is never reopened or reused."""

    def __init__(self, parent: Path, *, prefix: str = "run-") -> None:
        parent = Path(parent)
        parent.mkdir(parents=True, exist_ok=True)
        parent_stat = parent.lstat()
        if _is_link_or_reparse(parent_stat) or not stat.S_ISDIR(parent_stat.st_mode):
            raise InvalidRuntimeTreeError(f"workspace parent is not a real directory: {parent}")
        _require_nonempty_string(prefix, "workspace prefix")
        if _WORKSPACE_PREFIX_RE.fullmatch(prefix) is None or prefix in (".", ".."):
            raise InvalidRuntimeTreeError("workspace prefix must be one safe path component")
        self._parent = parent.resolve()
        self.path = Path(tempfile.mkdtemp(prefix=prefix, dir=parent))
        workspace_stat = self.path.lstat()
        if not stat.S_ISDIR(workspace_stat.st_mode):  # pragma: no cover - mkdtemp contract
            raise InvalidRuntimeTreeError(f"workspace is not a real directory: {self.path}")
        self._workspace_device = workspace_stat.st_dev
        self._workspace_inode = workspace_stat.st_ino
        self._generation = uuid.uuid4().hex
        self._closed = False

    @classmethod
    def create(cls, parent: Path, *, prefix: str = "run-") -> RunWorkspace:
        return cls(parent, prefix=prefix)

    def cleanup(self) -> None:
        if self._closed:
            return
        try:
            path_stat = self.path.lstat()
        except FileNotFoundError:
            self._closed = True
            return
        self._require_workspace_identity(self.path, path_stat)
        if self.path.parent.resolve() != self._parent:
            raise InvalidRuntimeTreeError(f"owned workspace escaped its parent: {self.path}")
        quarantine = self.path.parent / (
            f".{self.path.name}.retired-{self._generation}-"
            f"{path_stat.st_dev:x}-{path_stat.st_ino:x}-{uuid.uuid4().hex}"
        )
        current_stat = self.path.lstat()
        self._require_workspace_identity(self.path, current_stat)
        os.rename(self.path, quarantine)
        RuntimeStore._fsync_directory(quarantine.parent)
        self._closed = True
        try:
            _remove_tree(quarantine, current_stat)
        except OSError:
            # The owned name is already retired. A failed best-effort delete cannot
            # expose a replacement at the caller-visible workspace path.
            pass

    @staticmethod
    def _snapshot_payload(generation: str) -> dict[str, Any]:
        return {
            "schema": _WORKSPACE_SNAPSHOT_SCHEMA,
            "kind": _WORKSPACE_SNAPSHOT_KIND,
            "generation": generation,
        }

    @classmethod
    def _owned_snapshot_generation(cls, path: Path) -> str:
        try:
            path_stat = path.lstat()
        except FileNotFoundError as exc:
            raise InvalidRuntimeTreeError(f"snapshot does not exist: {path}") from exc
        if _is_link_or_reparse(path_stat) or not stat.S_ISDIR(path_stat.st_mode):
            raise InvalidRuntimeTreeError(f"snapshot is not a real directory: {path}")
        marker = path / _WORKSPACE_SNAPSHOT_MARKER
        try:
            raw = _read_bounded_regular_file(
                marker, _MAX_WORKSPACE_MARKER_BYTES, "snapshot ownership marker"
            )
            payload = json.loads(raw)
        except (
            StoreCorruptionError,
            UnicodeDecodeError,
            json.JSONDecodeError,
        ) as exc:
            raise InvalidRuntimeTreeError(
                f"refusing to replace snapshot without a valid ownership marker: {path}"
            ) from exc
        if not isinstance(payload, dict) or set(payload) != {"schema", "kind", "generation"}:
            raise InvalidRuntimeTreeError(f"snapshot ownership marker is invalid: {marker}")
        generation = payload["generation"]
        if (
            not isinstance(payload["schema"], int)
            or isinstance(payload["schema"], bool)
            or payload["schema"] != _WORKSPACE_SNAPSHOT_SCHEMA
            or payload["kind"] != _WORKSPACE_SNAPSHOT_KIND
            or not isinstance(generation, str)
            or _LEASE_ID_RE.fullmatch(generation) is None
            or raw != _json_file_bytes(cls._snapshot_payload(generation))
        ):
            raise InvalidRuntimeTreeError(f"snapshot ownership marker is invalid: {marker}")
        return generation

    def _write_snapshot_marker(self) -> None:
        self._require_live_workspace()
        marker = self.path / _WORKSPACE_SNAPSHOT_MARKER
        try:
            marker.lstat()
        except FileNotFoundError:
            pass
        else:
            raise InvalidRuntimeTreeError(
                f"workspace contains reserved snapshot marker path: {marker}"
            )
        descriptor, temporary_name = tempfile.mkstemp(prefix=".snapshot-marker-", dir=self.path)
        temporary = Path(temporary_name)
        try:
            with os.fdopen(descriptor, "wb") as output:
                output.write(_json_file_bytes(self._snapshot_payload(self._generation)))
                output.flush()
                os.fsync(output.fileno())
            os.chmod(temporary, 0o444)
            os.replace(temporary, marker)
            self._require_live_workspace()
            RuntimeStore._fsync_directory(self.path)
        finally:
            temporary.unlink(missing_ok=True)

    @staticmethod
    def _validate_snapshot_target(current: Path, parent: Path) -> None:
        if current.parent.resolve() != parent:
            raise InvalidRuntimeTreeError(
                "current snapshot must be a direct sibling of its run workspace"
            )
        if (
            _WORKSPACE_PREFIX_RE.fullmatch(current.name) is None
            or current.name in (".", "..")
        ):
            raise InvalidRuntimeTreeError("current snapshot name is unsafe")

    @staticmethod
    def _quarantine_match(current: Path, path: Path) -> re.Match[str] | None:
        return re.fullmatch(
            rf"\.{re.escape(current.name)}\.retired-"
            r"([0-9a-f]{32})-([0-9a-f]+)-([0-9a-f]+)-([0-9a-f]{32})",
            path.name,
        )

    @classmethod
    def _cleanup_quarantines(cls, current: Path) -> None:
        """Retry old snapshot deletes without letting cleanup block promotion."""

        for candidate in sorted(current.parent.iterdir()):
            match = cls._quarantine_match(current, candidate)
            if match is None:
                continue
            generation, device_hex, inode_hex, _nonce = match.groups()
            try:
                candidate_stat = candidate.lstat()
            except OSError:
                continue
            if (
                _is_link_or_reparse(candidate_stat)
                or not stat.S_ISDIR(candidate_stat.st_mode)
                or candidate_stat.st_dev != int(device_hex, 16)
                or candidate_stat.st_ino != int(inode_hex, 16)
            ):
                # Never follow or delete a substituted quarantine entry. It is not
                # part of the active snapshot namespace and therefore cannot poison
                # the next promotion.
                continue
            marker = candidate / _WORKSPACE_SNAPSHOT_MARKER
            try:
                marker.lstat()
            except FileNotFoundError:
                # The only safe markerless recovery is the tiny crash window after
                # every child was removed and before the root rmdir completed.
                try:
                    if any(candidate.iterdir()):
                        continue
                    candidate.rmdir()
                except OSError:
                    pass
                continue
            except OSError:
                continue
            else:
                try:
                    if cls._owned_snapshot_generation(candidate) != generation:
                        continue
                except (InvalidRuntimeTreeError, OSError):
                    continue
            try:
                cls._remove_snapshot_quarantine(
                    candidate, candidate_stat, generation
                )
            except (OSError, RuntimeStoreError):
                pass

    @classmethod
    def _remove_snapshot_quarantine(
        cls,
        path: Path,
        expected: os.stat_result,
        generation: str,
    ) -> None:
        """Delete children while retaining the ownership marker until the end."""

        path_stat = path.lstat()
        if (
            _is_link_or_reparse(path_stat)
            or not stat.S_ISDIR(path_stat.st_mode)
            or not _same_identity(path_stat, expected)
            or cls._owned_snapshot_generation(path) != generation
        ):
            raise StoreCorruptionError(f"refusing to remove unsafe snapshot: {path}")
        _make_writable(path)
        marker = path / _WORKSPACE_SNAPSHOT_MARKER
        for child in sorted(path.iterdir()):
            if child == marker:
                continue
            child_stat = child.lstat()
            if stat.S_ISDIR(child_stat.st_mode) and not _is_link_or_reparse(child_stat):
                _remove_tree(child, child_stat)
                continue
            if _is_link_or_reparse(child_stat):
                if stat.S_ISDIR(child_stat.st_mode):
                    os.rmdir(child)
                else:
                    child.unlink()
                continue
            _make_writable(child)
            child.unlink()

        _make_writable(marker)
        marker.unlink()
        try:
            path.rmdir()
        except OSError:
            # Restore the canonical marker when possible so the next promotion can
            # authenticate and retry this exact quarantine inode.
            try:
                remaining_stat = path.lstat()
                if _same_identity(remaining_stat, expected):
                    descriptor, temporary_name = tempfile.mkstemp(
                        prefix=".snapshot-marker-recovery-", dir=path
                    )
                    temporary = Path(temporary_name)
                    try:
                        with os.fdopen(descriptor, "wb") as output:
                            output.write(
                                _json_file_bytes(cls._snapshot_payload(generation))
                            )
                            output.flush()
                            os.fsync(output.fileno())
                        os.chmod(temporary, 0o444)
                        os.replace(temporary, marker)
                        RuntimeStore._fsync_directory(path)
                    finally:
                        temporary.unlink(missing_ok=True)
            except OSError:
                pass
            raise

    @classmethod
    def _retire_snapshot(cls, path: Path, current: Path) -> None:
        """Atomically move an owned snapshot aside, then delete it best-effort."""

        generation = cls._owned_snapshot_generation(path)
        path_stat = path.lstat()
        if _is_link_or_reparse(path_stat) or not stat.S_ISDIR(path_stat.st_mode):
            raise InvalidRuntimeTreeError(f"snapshot is not a real directory: {path}")
        quarantine = current.parent / (
            f".{current.name}.retired-{generation}-"
            f"{path_stat.st_dev:x}-{path_stat.st_ino:x}-{uuid.uuid4().hex}"
        )
        current_stat = path.lstat()
        if not _same_identity(path_stat, current_stat):
            raise InvalidRuntimeTreeError(f"snapshot changed before retirement: {path}")
        os.rename(path, quarantine)
        RuntimeStore._fsync_directory(current.parent)
        quarantine_stat = quarantine.lstat()
        if not _same_identity(path_stat, quarantine_stat):
            raise InvalidRuntimeTreeError(
                f"retired snapshot identity changed unexpectedly: {quarantine}"
            )
        try:
            cls._remove_snapshot_quarantine(
                quarantine, quarantine_stat, generation
            )
        except (OSError, RuntimeStoreError):
            # A later promotion retries this exact inode from its encoded identity.
            pass

    def promote_to(self, current: Path) -> WorkspacePromotion:
        """Promote this fresh staging tree to a guarded ``current`` snapshot.

        Each filesystem rename is atomic.  Replacing a non-empty directory
        needs two renames in portable Python, so the previous generated
        snapshot is retained as ``.<name>.last-good`` and restored if the
        second rename fails.  A later call also repairs an interrupted state
        where only that last-good snapshot remains.
        """

        if self._closed:
            raise RuntimeStoreError("workspace has already been cleaned or promoted")
        current = Path(current)
        current.parent.mkdir(parents=True, exist_ok=True)
        self._validate_snapshot_target(current, self._parent)
        promotion_lock = current.parent / f".{current.name}.promote.lock"
        with _FileLock(promotion_lock, None):
            return self._promote_locked(current)

    def _promote_locked(self, current: Path) -> WorkspacePromotion:
        """Promote while holding the cross-process lock for ``current``."""

        self._require_live_workspace()
        last_good = current.parent / f".{current.name}.last-good"
        self._cleanup_quarantines(current)

        current_exists = current.exists() or current.is_symlink()
        last_good_exists = last_good.exists() or last_good.is_symlink()
        recovered_interrupted = False
        if not current_exists and last_good_exists:
            self._owned_snapshot_generation(last_good)
            os.rename(last_good, current)
            RuntimeStore._fsync_directory(current.parent)
            current_exists = True
            last_good_exists = False
            recovered_interrupted = True

        if current_exists:
            self._owned_snapshot_generation(current)
        if last_good_exists:
            self._owned_snapshot_generation(last_good)

        self._write_snapshot_marker()
        if not current_exists:
            try:
                os.rename(self.path, current)
                RuntimeStore._fsync_directory(current.parent)
                self._require_workspace_identity(current)
                if self._owned_snapshot_generation(current) != self._generation:
                    raise RuntimeStoreError(
                        "promoted snapshot generation does not match workspace"
                    )
            except Exception:
                # If validation failed after the rename, put the still-owned workspace
                # back at its private path. Never move an inode we do not own.
                if (current.exists() or current.is_symlink()) and not (
                    self.path.exists() or self.path.is_symlink()
                ):
                    self._require_workspace_identity(current)
                    os.rename(current, self.path)
                    RuntimeStore._fsync_directory(current.parent)
                raise
            self._closed = True
            return WorkspacePromotion(
                current=current,
                replaced=False,
                recovered_interrupted=recovered_interrupted,
            )

        if last_good_exists:
            self._retire_snapshot(last_good, current)
        previous_generation = self._owned_snapshot_generation(current)
        previous_moved = False
        try:
            os.rename(current, last_good)
            previous_moved = True
            RuntimeStore._fsync_directory(current.parent)
            os.rename(self.path, current)
            RuntimeStore._fsync_directory(current.parent)
            self._require_workspace_identity(current)
            if self._owned_snapshot_generation(current) != self._generation:
                raise RuntimeStoreError(
                    "promoted snapshot generation does not match workspace"
                )
        except Exception as promotion_error:
            if not previous_moved:
                raise
            try:
                if current.exists() or current.is_symlink():
                    self._require_workspace_identity(current)
                    if self.path.exists() or self.path.is_symlink():
                        raise InvalidRuntimeTreeError(
                            "cannot recover promoted workspace because its private path exists"
                        )
                    os.rename(current, self.path)
                    RuntimeStore._fsync_directory(current.parent)
                else:
                    self._require_live_workspace()
                os.rename(last_good, current)
                RuntimeStore._fsync_directory(current.parent)
                if self._owned_snapshot_generation(current) != previous_generation:
                    raise RuntimeStoreError(
                        "restored snapshot generation does not match last-good"
                    )
            except Exception as rollback_error:
                raise RuntimeStoreError(
                    "snapshot promotion and last-good rollback both failed; "
                    f"recover {last_good} to {current}: {rollback_error}"
                ) from promotion_error
            raise
        self._closed = True
        self._retire_snapshot(last_good, current)
        return WorkspacePromotion(
            current=current,
            replaced=True,
            recovered_interrupted=recovered_interrupted,
        )

    def _require_workspace_identity(
        self, path: Path, path_stat: os.stat_result | None = None
    ) -> None:
        try:
            path_stat = path.lstat() if path_stat is None else path_stat
        except FileNotFoundError as exc:
            raise InvalidRuntimeTreeError(f"workspace disappeared: {path}") from exc
        if _is_link_or_reparse(path_stat) or not stat.S_ISDIR(path_stat.st_mode):
            raise InvalidRuntimeTreeError(f"workspace is not a real directory: {path}")
        if (
            path_stat.st_dev != self._workspace_device
            or path_stat.st_ino != self._workspace_inode
        ):
            raise InvalidRuntimeTreeError(
                f"owned workspace was replaced by another directory: {path}"
            )

    def _require_live_workspace(self) -> None:
        self._require_workspace_identity(self.path)
        if self.path.parent.resolve() != self._parent:
            raise InvalidRuntimeTreeError(f"workspace escaped its parent: {self.path}")

    def __enter__(self) -> RunWorkspace:
        if self._closed:
            raise RuntimeStoreError("workspace has already been cleaned")
        return self

    def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
        self.cleanup()


@dataclass(frozen=True)
class _RecipeRecord:
    recipe: RuntimeRecipe
    recipe_digest: str
    tree_digest: str
    created_at: float
    last_accessed_at: float
    schema: int = RECIPE_RECORD_SCHEMA

    @classmethod
    def from_payload(cls, payload: object, expected_digest: str) -> _RecipeRecord:
        if not isinstance(payload, dict):
            raise StoreCorruptionError("recipe record must be an object")
        _require_exact_keys(
            payload,
            {
                "schema",
                "recipe",
                "recipe_digest",
                "tree_digest",
                "created_at",
                "last_accessed_at",
            },
            "recipe record",
        )
        if (
            not isinstance(payload["schema"], int)
            or isinstance(payload["schema"], bool)
            or payload["schema"] != RECIPE_RECORD_SCHEMA
        ):
            raise StoreCorruptionError(
                f"recipe record schema must be exactly {RECIPE_RECORD_SCHEMA}"
            )
        recipe = RuntimeRecipe.from_payload(payload["recipe"])
        recipe_digest = _require_digest(payload["recipe_digest"], "recipe record digest")
        if recipe_digest != expected_digest or recipe.digest() != recipe_digest:
            raise StoreCorruptionError("recipe record identity does not match its recipe")
        tree_digest = _require_digest(payload["tree_digest"], "recipe tree digest")
        created_at = _require_timestamp(payload["created_at"], "recipe created_at")
        last_accessed_at = _require_timestamp(
            payload["last_accessed_at"], "recipe last_accessed_at"
        )
        if last_accessed_at < created_at:
            raise StoreCorruptionError("recipe last_accessed_at predates created_at")
        return cls(
            recipe=recipe,
            recipe_digest=recipe_digest,
            tree_digest=tree_digest,
            created_at=created_at,
            last_accessed_at=last_accessed_at,
        )

    def payload(self) -> dict[str, Any]:
        return {
            "schema": self.schema,
            "recipe": self.recipe.payload(),
            "recipe_digest": self.recipe_digest,
            "tree_digest": self.tree_digest,
            "created_at": self.created_at,
            "last_accessed_at": self.last_accessed_at,
        }


@dataclass(frozen=True)
class _LeaseRecord:
    lease_id: str
    recipe_digest: str | None
    tree_digest: str | None
    blob_digests: tuple[str, ...]
    created_at: float
    schema: int = LEASE_SCHEMA

    @classmethod
    def from_payload(cls, payload: object, expected_id: str) -> _LeaseRecord:
        if not isinstance(payload, dict):
            raise StoreCorruptionError("lease must be an object")
        _require_exact_keys(
            payload,
            {
                "schema",
                "lease_id",
                "recipe_digest",
                "tree_digest",
                "blob_digests",
                "created_at",
            },
            "lease",
        )
        if (
            not isinstance(payload["schema"], int)
            or isinstance(payload["schema"], bool)
            or payload["schema"] != LEASE_SCHEMA
        ):
            raise StoreCorruptionError(f"lease schema must be exactly {LEASE_SCHEMA}")
        lease_id = payload["lease_id"]
        if (
            not isinstance(lease_id, str)
            or _LEASE_ID_RE.fullmatch(lease_id) is None
            or lease_id != expected_id
        ):
            raise StoreCorruptionError("lease identity is invalid")
        blobs = payload["blob_digests"]
        if not isinstance(blobs, list):
            raise StoreCorruptionError("lease blob_digests must be an array")
        blob_digests = tuple(_require_digest(value, "lease blob digest") for value in blobs)
        if list(blob_digests) != sorted(set(blob_digests)):
            raise StoreCorruptionError("lease blob_digests must be unique and sorted")
        recipe_digest = payload["recipe_digest"]
        tree_digest = payload["tree_digest"]
        if (recipe_digest is None) != (tree_digest is None):
            raise StoreCorruptionError(
                "lease recipe_digest and tree_digest must both be set or both be null"
            )
        if recipe_digest is None:
            if not blob_digests:
                raise StoreCorruptionError("blob-only lease must protect at least one blob")
        else:
            recipe_digest = _require_digest(recipe_digest, "lease recipe digest")
            tree_digest = _require_digest(tree_digest, "lease tree digest")
        return cls(
            lease_id=lease_id,
            recipe_digest=recipe_digest,
            tree_digest=tree_digest,
            blob_digests=blob_digests,
            created_at=_require_timestamp(payload["created_at"], "lease created_at"),
        )

    def payload(self) -> dict[str, Any]:
        return {
            "schema": self.schema,
            "lease_id": self.lease_id,
            "recipe_digest": self.recipe_digest,
            "tree_digest": self.tree_digest,
            "blob_digests": list(self.blob_digests),
            "created_at": self.created_at,
        }


_LOCAL_LOCKS_GUARD = threading.Lock()
_LOCAL_LOCKS: weakref.WeakValueDictionary[str, threading.Lock] = (
    weakref.WeakValueDictionary()
)


def _local_lock(path: Path) -> threading.Lock:
    key = os.path.abspath(os.fspath(path))
    with _LOCAL_LOCKS_GUARD:
        return _LOCAL_LOCKS.setdefault(key, threading.Lock())


class _FileLock:
    """A persistent lock file with process-local and cross-process exclusion."""

    def __init__(self, path: Path, timeout: float | None) -> None:
        if timeout is not None and (
            isinstance(timeout, bool)
            or not isinstance(timeout, (int, float))
            or not math.isfinite(timeout)
            or timeout < 0
        ):
            raise ValueError("lock timeout must be a finite non-negative number or None")
        self.path = path
        self.timeout = timeout
        self._local = _local_lock(path)
        self._local_acquired = False
        self._descriptor: int | None = None

    def _require_descriptor_identity(
        self, descriptor: int, initial: os.stat_result | None
    ) -> None:
        descriptor_stat = os.fstat(descriptor)
        try:
            path_stat = self.path.lstat()
        except FileNotFoundError as exc:
            raise StoreCorruptionError(f"lock path disappeared: {self.path}") from exc
        if (
            _is_link_or_reparse(descriptor_stat)
            or _is_link_or_reparse(path_stat)
            or not stat.S_ISREG(descriptor_stat.st_mode)
            or not stat.S_ISREG(path_stat.st_mode)
            or not _same_identity(descriptor_stat, path_stat)
            or (initial is not None and not _same_identity(initial, descriptor_stat))
        ):
            raise StoreCorruptionError(
                f"lock path does not identify the opened regular file: {self.path}"
            )

    def __enter__(self) -> _FileLock:
        started = time.monotonic()
        if self.timeout is None:
            acquired = self._local.acquire()
        else:
            acquired = self._local.acquire(timeout=self.timeout)
        if not acquired:
            raise LockTimeoutError(f"timed out waiting for lock {self.path.name}")
        self._local_acquired = True
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            parent_stat = self.path.parent.lstat()
            if _is_link_or_reparse(parent_stat) or not stat.S_ISDIR(parent_stat.st_mode):
                raise StoreCorruptionError(
                    f"lock parent is not a real directory: {self.path.parent}"
                )
            try:
                initial = self.path.lstat()
            except FileNotFoundError:
                initial = None
            if initial is not None and (
                _is_link_or_reparse(initial) or not stat.S_ISREG(initial.st_mode)
            ):
                raise StoreCorruptionError(f"lock path is unsafe: {self.path}")
            flags = os.O_CREAT | os.O_RDWR
            flags |= getattr(os, "O_CLOEXEC", 0)
            flags |= getattr(os, "O_NOFOLLOW", 0)
            flags |= getattr(os, "O_BINARY", 0)
            descriptor = os.open(self.path, flags, 0o600)
            self._descriptor = descriptor
            self._require_descriptor_identity(descriptor, initial)
            while True:
                try:
                    self._acquire_os_lock(descriptor)
                    self._require_descriptor_identity(descriptor, initial)
                    return self
                except OSError as exc:
                    if exc.errno not in (errno.EACCES, errno.EAGAIN):
                        raise
                    if self.timeout is not None:
                        remaining = self.timeout - (time.monotonic() - started)
                        if remaining <= 0:
                            raise LockTimeoutError(
                                f"timed out waiting for lock {self.path.name}"
                            ) from exc
                        time.sleep(min(0.05, remaining))
                    else:
                        time.sleep(0.05)
        except Exception:
            if self._descriptor is not None:
                os.close(self._descriptor)
                self._descriptor = None
            self._local.release()
            self._local_acquired = False
            raise

    @staticmethod
    def _acquire_os_lock(descriptor: int) -> None:
        if os.name == "nt":
            import msvcrt

            if os.fstat(descriptor).st_size == 0:
                os.write(descriptor, b"\0")
                os.fsync(descriptor)
            os.lseek(descriptor, 0, os.SEEK_SET)
            msvcrt.locking(descriptor, msvcrt.LK_NBLCK, 1)
        else:
            import fcntl

            fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)

    @staticmethod
    def _release_os_lock(descriptor: int) -> None:
        if os.name == "nt":
            import msvcrt

            os.lseek(descriptor, 0, os.SEEK_SET)
            msvcrt.locking(descriptor, msvcrt.LK_UNLCK, 1)
        else:
            import fcntl

            fcntl.flock(descriptor, fcntl.LOCK_UN)

    def __exit__(self, _type: object, _value: object, _traceback: object) -> None:
        if not self._local_acquired:
            return
        descriptor = self._descriptor
        self._descriptor = None
        try:
            if descriptor is not None:
                try:
                    self._release_os_lock(descriptor)
                finally:
                    os.close(descriptor)
        finally:
            self._local.release()
            self._local_acquired = False


@dataclass
class _LeaseGuard:
    store: RuntimeStore
    path: Path
    lock_path: Path
    lock: _FileLock
    released: bool = False

    def release(self) -> None:
        if self.released:
            return
        with self.store._global_lock():
            try:
                self.path.unlink(missing_ok=True)
            finally:
                try:
                    self.lock.__exit__(None, None, None)
                finally:
                    try:
                        self.lock_path.unlink(missing_ok=True)
                        RuntimeStore._fsync_directory(self.path.parent)
                    finally:
                        self.released = True


@dataclass
class _ActiveLease:
    guard: _LeaseGuard
    stored: StoredRuntime

    def release(self) -> None:
        self.guard.release()


@dataclass(frozen=True)
class _RecipeState:
    path: Path
    record: _RecipeRecord
    manifest: TreeManifest


class RuntimeStore:
    """Versioned content-addressed cache for installed client runtimes."""

    def __init__(self, cache_root: Path, *, clock: Callable[[], float] = time.time) -> None:
        self.cache_root = Path(cache_root)
        self.root = self.cache_root / STORE_DIRECTORY / STORE_VERSION
        self.blobs_dir = self.root / "blobs"
        self._blob_objects_dir = self.blobs_dir / "sha256"
        self.trees_dir = self.root / "trees"
        self.recipes_dir = self.root / "recipes"
        self.leases_dir = self.root / "leases"
        self.tmp_dir = self.root / "tmp"
        self.trash_dir = self.root / "trash"
        self._active_leases_dir = self.leases_dir / "active"
        self._locks_dir = self.leases_dir / "locks"
        self._clock = clock
        self._metrics_lock = threading.Lock()
        self._metrics = StoreMetrics()
        self._initialize_layout()

    def _initialize_layout(self) -> None:
        self.cache_root.mkdir(parents=True, exist_ok=True)
        for directory in (
            self.cache_root,
            self.cache_root / STORE_DIRECTORY,
            self.root,
            self.blobs_dir,
            self._blob_objects_dir,
            self.trees_dir,
            self.recipes_dir,
            self.leases_dir,
            self.tmp_dir,
            self.trash_dir,
            self._active_leases_dir,
            self._locks_dir,
        ):
            self._ensure_real_directory(directory)

    @staticmethod
    def _ensure_real_directory(directory: Path) -> None:
        try:
            directory.mkdir()
        except FileExistsError:
            pass
        try:
            directory_stat = directory.lstat()
        except FileNotFoundError as exc:
            raise StoreCorruptionError(f"store directory disappeared: {directory}") from exc
        if _is_link_or_reparse(directory_stat) or not stat.S_ISDIR(directory_stat.st_mode):
            raise StoreCorruptionError(f"store path is not a real directory: {directory}")

    def _now(self) -> float:
        try:
            return _require_timestamp(self._clock(), "store clock")
        except StoreCorruptionError as exc:
            raise RuntimeStoreError(str(exc)) from exc

    @property
    def metrics(self) -> StoreMetrics:
        with self._metrics_lock:
            return self._metrics

    def _record_metrics(
        self,
        *,
        hits: int = 0,
        misses: int = 0,
        pruned: int = 0,
        pruned_trees: int = 0,
        pruned_blobs: int = 0,
        pruned_bytes: int = 0,
    ) -> None:
        with self._metrics_lock:
            current = self._metrics
            self._metrics = StoreMetrics(
                hits=current.hits + hits,
                misses=current.misses + misses,
                pruned=current.pruned + pruned,
                pruned_trees=current.pruned_trees + pruned_trees,
                pruned_blobs=current.pruned_blobs + pruned_blobs,
                pruned_bytes=current.pruned_bytes + pruned_bytes,
            )

    @staticmethod
    def _recipe_identity(
        recipe: RuntimeRecipe | StoredRuntime | str,
    ) -> tuple[str, RuntimeRecipe | None]:
        if isinstance(recipe, RuntimeRecipe):
            return recipe.digest(), recipe
        if isinstance(recipe, StoredRuntime):
            _require_digest(recipe.recipe_digest, "stored recipe digest")
            if recipe.recipe.digest() != recipe.recipe_digest:
                raise RuntimeStoreError("stored runtime recipe identity is inconsistent")
            return recipe.recipe_digest, recipe.recipe
        if isinstance(recipe, str):
            try:
                return _require_digest(recipe, "recipe digest"), None
            except StoreCorruptionError as exc:
                raise RuntimeStoreError(str(exc)) from exc
        raise TypeError("recipe must be RuntimeRecipe, StoredRuntime, or a recipe digest")

    @staticmethod
    def _addressed_path(directory: Path, digest: str, suffix: str = "") -> Path:
        _require_digest(digest, "content digest")
        return directory / digest[:2] / f"{digest}{suffix}"

    def path_for_blob(self, digest: str) -> Path:
        return self._addressed_path(self._blob_objects_dir, digest)

    def path_for_tree(self, digest: str) -> Path:
        return self._addressed_path(self.trees_dir, digest, ".json")

    def path_for_recipe(self, recipe: RuntimeRecipe | StoredRuntime | str) -> Path:
        digest, _expected = self._recipe_identity(recipe)
        return self._addressed_path(self.recipes_dir, digest, ".json")

    def admit_blob(self, source_path: Path, expected_sha256: str) -> Path:
        """Verify and atomically admit one downloaded dependency blob.

        The caller owns downloading to a temporary path.  An exact existing
        object is reused, corrupt content is repaired only from newly verified
        source bytes, and the returned store object is read-only.
        """

        try:
            expected_sha256 = _require_digest(expected_sha256, "expected blob digest")
        except StoreCorruptionError as exc:
            raise RuntimeStoreError(str(exc)) from exc
        source_path = Path(source_path)
        actual_digest, size, mode = self._hash_source_file(source_path)
        if actual_digest != expected_sha256:
            raise InvalidRuntimeTreeError(
                "dependency blob SHA-256 mismatch: "
                f"expected {expected_sha256}, got {actual_digest}"
            )
        entry = TreeEntry("admitted-blob", size, expected_sha256, mode)
        with self._global_lock():
            self._publish_blob(source_path, entry)
            destination = self.path_for_blob(expected_sha256)
            os.chmod(destination, 0o444)
            self._validate_blob(destination, expected_sha256, size)
            return destination

    @contextmanager
    def lease_blob(self, digest: str) -> Iterator[Path]:
        """Protect one admitted installer/dependency blob while it is consumed."""

        try:
            digest = _require_digest(digest, "leased blob digest")
        except StoreCorruptionError as exc:
            raise RuntimeStoreError(str(exc)) from exc
        with self._global_lock():
            path = self.path_for_blob(digest)
            try:
                size = path.lstat().st_size
            except FileNotFoundError as exc:
                raise RecipeNotFoundError(f"runtime blob is not stored: {digest}") from exc
            self._validate_blob(path, digest, size)
            lease_guard = self._write_lease(
                recipe_digest=None,
                tree_digest=None,
                blob_digests=(digest,),
            )
        try:
            yield path
        finally:
            lease_guard.release()

    @contextmanager
    def recipe_lock(
        self,
        recipe: RuntimeRecipe | StoredRuntime | str,
        *,
        timeout: float | None = None,
    ) -> Iterator[None]:
        digest, _expected = self._recipe_identity(recipe)
        path = self._locks_dir / f"recipe-{digest}.lock"
        with _FileLock(path, timeout):
            yield

    @contextmanager
    def _global_lock(self, *, timeout: float | None = None) -> Iterator[None]:
        with _FileLock(self._locks_dir / "store-gc.lock", timeout):
            yield

    def lookup(self, recipe: RuntimeRecipe) -> StoredRuntime | None:
        """Return a fully validated hit, or ``None`` for absent/corrupt content."""

        with self.recipe_lock(recipe):
            try:
                active = self._acquire_runtime_lease_locked(recipe.digest(), recipe, touch=True)
            except (RecipeNotFoundError, StoreCorruptionError):
                self._record_metrics(misses=1)
                return None
            try:
                stored = active.stored
            finally:
                active.release()
        self._record_metrics(hits=1)
        return stored

    def validate(
        self, recipe: RuntimeRecipe | StoredRuntime | str
    ) -> StoredRuntime:
        """Validate a recipe, manifest, and every referenced blob or raise."""

        digest, expected = self._recipe_identity(recipe)
        with self.recipe_lock(digest):
            active = self._acquire_runtime_lease_locked(digest, expected, touch=False)
            try:
                return active.stored
            finally:
                active.release()

    @contextmanager
    def lease(
        self, recipe: RuntimeRecipe | StoredRuntime | str
    ) -> Iterator[StoredRuntime]:
        """Protect a validated runtime from GC for the duration of the context."""

        digest, expected = self._recipe_identity(recipe)
        with self.recipe_lock(digest):
            active = self._acquire_runtime_lease_locked(digest, expected, touch=True)
        try:
            yield active.stored
        finally:
            active.release()

    def publish(self, recipe: RuntimeRecipe, source_tree: Path) -> StoredRuntime:
        """Atomically publish a complete source tree for one recipe."""

        digest = recipe.digest()
        with self.recipe_lock(digest):
            existing = self._try_existing_locked(digest, recipe)
            if existing is not None:
                return existing
            return self._publish_locked(recipe, Path(source_tree))

    def get_or_create(
        self,
        recipe: RuntimeRecipe,
        builder: Callable[[Path], None],
    ) -> StoredRuntime:
        """Return/build one runtime; prefer a lease or the combined materializer.

        The returned metadata is not itself a GC lease, so separating this call
        from later materialization can reopen a collection window.
        """

        warnings.warn(
            "RuntimeStore.get_or_create() returns without a GC lease; use "
            "get_or_create_lease() or materialize_get_or_create()",
            DeprecationWarning,
            stacklevel=2,
        )
        with self.get_or_create_lease(recipe, builder) as stored:
            return stored

    def materialize_get_or_create(
        self,
        recipe: RuntimeRecipe,
        builder: Callable[[Path], None],
        destination: Path,
    ) -> Path:
        """Build/find and materialize while one uninterrupted GC lease is live."""

        with self.get_or_create_lease(recipe, builder) as stored:
            return self.materialize(stored, destination)

    @contextmanager
    def get_or_create_lease(
        self,
        recipe: RuntimeRecipe,
        builder: Callable[[Path], None],
    ) -> Iterator[StoredRuntime]:
        """Build or find a runtime and keep it GC-reachable across caller work."""

        digest = recipe.digest()
        active: _ActiveLease | None = None
        try:
            with self.recipe_lock(digest):
                try:
                    active = self._acquire_runtime_lease_locked(
                        digest, recipe, touch=True
                    )
                except (RecipeNotFoundError, StoreCorruptionError):
                    active = None
                if active is not None:
                    self._record_metrics(hits=1)
                else:
                    self._record_metrics(misses=1)
                    staging = Path(
                        tempfile.mkdtemp(
                            prefix=f"build-{digest}-", dir=self.tmp_dir
                        )
                    )
                    try:
                        builder(staging)
                        active = self._publish_active_locked(recipe, staging)
                    finally:
                        self._quarantine_builder_staging(staging)
            if active is None:  # pragma: no cover - guarded by build/exception paths
                raise RuntimeStoreError("runtime construction produced no active lease")
            yield active.stored
        finally:
            if active is not None:
                active.release()

    def _try_existing_locked(
        self, digest: str, recipe: RuntimeRecipe
    ) -> StoredRuntime | None:
        try:
            active = self._acquire_runtime_lease_locked(digest, recipe, touch=False)
        except (RecipeNotFoundError, StoreCorruptionError):
            return None
        try:
            return active.stored
        finally:
            active.release()

    def _acquire_runtime_lease_locked(
        self,
        recipe_digest: str,
        expected_recipe: RuntimeRecipe | None,
        *,
        touch: bool,
    ) -> _ActiveLease:
        with self._global_lock():
            record = self._load_recipe_record(recipe_digest)
            if expected_recipe is not None and record.recipe != expected_recipe:
                raise StoreCorruptionError("recipe record payload differs from requested recipe")
            manifest = self._load_tree_manifest(record.tree_digest)
            lease_guard = self._write_lease(
                recipe_digest=record.recipe_digest,
                tree_digest=manifest.digest(),
                blob_digests=(*manifest.blob_digests, record.recipe.installer_sha256),
            )

        try:
            stored = self._stored_runtime(record, manifest)
            active = _ActiveLease(lease_guard, stored)
        except Exception:
            lease_guard.release()
            raise
        try:
            self._validate_manifest_blobs(manifest)
            if touch:
                accessed = max(record.last_accessed_at, self._now())
                touched = _RecipeRecord(
                    recipe=record.recipe,
                    recipe_digest=record.recipe_digest,
                    tree_digest=record.tree_digest,
                    created_at=record.created_at,
                    last_accessed_at=accessed,
                )
                self._atomic_write(
                    self.path_for_recipe(recipe_digest), _json_file_bytes(touched.payload())
                )
                active.stored = self._stored_runtime(touched, manifest)
            return active
        except Exception:
            active.release()
            raise

    def _publish_locked(self, recipe: RuntimeRecipe, source_tree: Path) -> StoredRuntime:
        active = self._publish_active_locked(recipe, source_tree)
        try:
            return active.stored
        finally:
            active.release()

    def _publish_active_locked(
        self, recipe: RuntimeRecipe, source_tree: Path
    ) -> _ActiveLease:
        manifest = self._scan_source_tree(source_tree)
        with self._global_lock():
            writer_lease_guard = self._write_lease(
                recipe_digest=recipe.digest(),
                tree_digest=manifest.digest(),
                blob_digests=(*manifest.blob_digests, recipe.installer_sha256),
            )

        try:
            now = self._now()
            writer = _ActiveLease(
                writer_lease_guard,
                StoredRuntime(
                    recipe=recipe,
                    recipe_digest=recipe.digest(),
                    tree_digest=manifest.digest(),
                    manifest=manifest,
                    created_at=now,
                    last_accessed_at=now,
                    manifest_path=self.path_for_tree(manifest.digest()),
                ),
            )
        except Exception:
            writer_lease_guard.release()
            raise
        try:
            self._publish_blobs(source_tree, manifest)
            self._verify_source_shape(source_tree, manifest)
            tree_path = self.path_for_tree(manifest.digest())
            self._atomic_write(tree_path, _json_file_bytes(manifest.payload()))
            self._validate_manifest_blobs(manifest)
            record = _RecipeRecord(
                recipe=recipe,
                recipe_digest=recipe.digest(),
                tree_digest=manifest.digest(),
                created_at=now,
                last_accessed_at=now,
            )
            with self._global_lock():
                self._atomic_write(
                    self.path_for_recipe(recipe.digest()),
                    _json_file_bytes(record.payload()),
                )
            writer.stored = self._stored_runtime(record, manifest)
            return writer
        except Exception:
            writer.release()
            raise

    def _stored_runtime(
        self, record: _RecipeRecord, manifest: TreeManifest
    ) -> StoredRuntime:
        return StoredRuntime(
            recipe=record.recipe,
            recipe_digest=record.recipe_digest,
            tree_digest=record.tree_digest,
            manifest=manifest,
            created_at=record.created_at,
            last_accessed_at=record.last_accessed_at,
            manifest_path=self.path_for_tree(record.tree_digest),
        )

    def _scan_source_tree(self, source_tree: Path) -> TreeManifest:
        self._require_source_root(source_tree)
        entries: list[TreeEntry] = []
        for path in sorted(
            source_tree.rglob("*"),
            key=lambda item: item.relative_to(source_tree).as_posix(),
        ):
            relative = path.relative_to(source_tree).as_posix()
            real, path_stat = self._resolve_source_entry(source_tree, path, relative)
            if stat.S_ISDIR(path_stat.st_mode):
                continue
            if not stat.S_ISREG(path_stat.st_mode):
                raise InvalidRuntimeTreeError(f"runtime tree contains a special file: {relative}")
            try:
                _validate_manifest_path(relative)
            except StoreCorruptionError as exc:
                raise InvalidRuntimeTreeError(str(exc)) from exc
            digest, size, mode = self._hash_source_file(real)
            entries.append(TreeEntry(relative, size, digest, mode))
        return TreeManifest(tuple(entries))

    @classmethod
    def _resolve_source_entry(
        cls, source_tree: Path, path: Path, relative: str
    ) -> tuple[Path, os.stat_result]:
        """Map one scanned runtime path to the real file the store must read.

        Mojang's bundled Java runtimes ship internal relative symbolic links, so a
        link is dereferenced only when its target stays inside the runtime tree and
        is itself a regular file.  Everything the store then opens, hashes, and
        materializes is a real file, so no link is ever published or restored.
        """

        path_stat = path.lstat()
        if not _is_link_or_reparse(path_stat):
            return path, path_stat
        root = cls._resolved_source_root(source_tree)
        try:
            real = path.resolve(strict=True)
        except (OSError, RuntimeError) as exc:
            raise InvalidRuntimeTreeError(
                f"runtime tree contains an unresolvable symbolic link: {relative}"
            ) from exc
        try:
            real.relative_to(root)
        except ValueError as exc:
            raise InvalidRuntimeTreeError(
                f"runtime tree contains an escaping symbolic link: {relative}"
            ) from exc
        real_stat = real.lstat()
        if _is_link_or_reparse(real_stat) or not stat.S_ISREG(real_stat.st_mode):
            raise InvalidRuntimeTreeError(
                "runtime tree symbolic link does not resolve to a regular file: "
                f"{relative}"
            )
        return real, real_stat

    @staticmethod
    def _resolved_source_root(source_tree: Path) -> Path:
        try:
            return source_tree.resolve(strict=True)
        except (OSError, RuntimeError) as exc:
            raise InvalidRuntimeTreeError(
                f"runtime source tree is not resolvable: {source_tree}"
            ) from exc

    @staticmethod
    def _require_source_root(source_tree: Path) -> None:
        try:
            root_stat = source_tree.lstat()
        except FileNotFoundError as exc:
            raise InvalidRuntimeTreeError(
                f"runtime source tree does not exist: {source_tree}"
            ) from exc
        if _is_link_or_reparse(root_stat) or not stat.S_ISDIR(root_stat.st_mode):
            raise InvalidRuntimeTreeError(
                f"runtime source tree is not a real directory: {source_tree}"
            )

    @staticmethod
    def _open_source_file(path: Path) -> tuple[BinaryIO, os.stat_result]:
        try:
            initial_stat = path.lstat()
        except FileNotFoundError as exc:
            raise InvalidRuntimeTreeError(f"runtime file does not exist: {path}") from exc
        if _is_link_or_reparse(initial_stat) or not stat.S_ISREG(initial_stat.st_mode):
            raise InvalidRuntimeTreeError(f"runtime path is not a regular file: {path}")
        flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
        try:
            descriptor = os.open(path, flags)
        except OSError as exc:
            raise InvalidRuntimeTreeError(f"cannot safely open runtime file {path}: {exc}") from exc
        try:
            descriptor_stat = os.fstat(descriptor)
            current_stat = path.lstat()
            if (
                _is_link_or_reparse(descriptor_stat)
                or _is_link_or_reparse(current_stat)
                or not stat.S_ISREG(descriptor_stat.st_mode)
                or not stat.S_ISREG(current_stat.st_mode)
            ):
                raise InvalidRuntimeTreeError(f"runtime path is not a regular file: {path}")
            if (
                not _same_identity(initial_stat, descriptor_stat)
                or not _same_identity(descriptor_stat, current_stat)
            ):
                raise InvalidRuntimeTreeError(f"runtime path changed while opening: {path}")
            return os.fdopen(descriptor, "rb"), descriptor_stat
        except Exception:
            os.close(descriptor)
            raise

    def _hash_source_file(self, path: Path) -> tuple[str, int, int]:
        stream, descriptor_stat = self._open_source_file(path)
        digest = hashlib.sha256()
        size = 0
        with stream:
            for chunk in iter(lambda: stream.read(_COPY_CHUNK_SIZE), b""):
                digest.update(chunk)
                size += len(chunk)
        if size != descriptor_stat.st_size:
            raise InvalidRuntimeTreeError(f"runtime file changed while hashing: {path}")
        return digest.hexdigest(), size, stat.S_IMODE(descriptor_stat.st_mode) & 0o777

    def _publish_blobs(self, source_tree: Path, manifest: TreeManifest) -> None:
        published: set[str] = set()
        for entry in manifest.entries:
            linked = source_tree.joinpath(*PurePosixPath(entry.path).parts)
            source, _source_stat = self._resolve_source_entry(
                source_tree, linked, entry.path
            )
            if entry.sha256 in published:
                digest, size, _mode = self._hash_source_file(source)
                if digest != entry.sha256 or size != entry.size:
                    raise InvalidRuntimeTreeError(
                        f"runtime file changed while publishing: {entry.path}"
                    )
                continue
            self._publish_blob(source, entry)
            published.add(entry.sha256)

    def _publish_blob(self, source: Path, entry: TreeEntry) -> None:
        destination = self.path_for_blob(entry.sha256)
        self._ensure_real_directory(destination.parent)
        source_stream, source_stat = self._open_source_file(source)
        try:
            descriptor, temporary_name = tempfile.mkstemp(prefix="blob-", dir=self.tmp_dir)
        except Exception:
            source_stream.close()
            raise
        temporary = Path(temporary_name)
        digest = hashlib.sha256()
        size = 0
        try:
            with source_stream, os.fdopen(descriptor, "wb") as output:
                for chunk in iter(lambda: source_stream.read(_COPY_CHUNK_SIZE), b""):
                    digest.update(chunk)
                    size += len(chunk)
                    output.write(chunk)
                output.flush()
                os.fsync(output.fileno())
            mode = stat.S_IMODE(source_stat.st_mode) & 0o777
            if (
                digest.hexdigest() != entry.sha256
                or size != entry.size
                or source_stat.st_size != entry.size
                or mode != entry.mode
            ):
                raise InvalidRuntimeTreeError(
                    f"runtime file changed while publishing: {entry.path}"
                )
            if self._blob_is_valid(destination, entry.sha256, entry.size):
                return
            os.chmod(temporary, 0o444)
            os.replace(temporary, destination)
            self._fsync_directory(destination.parent)
        finally:
            temporary.unlink(missing_ok=True)

    def _verify_source_shape(self, source_tree: Path, manifest: TreeManifest) -> None:
        self._require_source_root(source_tree)
        expected = {entry.path: (entry.size, entry.mode) for entry in manifest.entries}
        actual: dict[str, tuple[int, int]] = {}
        for path in source_tree.rglob("*"):
            relative = path.relative_to(source_tree).as_posix()
            _real, path_stat = self._resolve_source_entry(source_tree, path, relative)
            if stat.S_ISDIR(path_stat.st_mode):
                continue
            if not stat.S_ISREG(path_stat.st_mode):
                raise InvalidRuntimeTreeError(f"runtime tree contains a special file: {relative}")
            actual[relative] = (path_stat.st_size, stat.S_IMODE(path_stat.st_mode) & 0o777)
        if actual != expected:
            raise InvalidRuntimeTreeError("runtime tree changed while it was being published")

    def _blob_is_valid(self, path: Path, expected_digest: str, expected_size: int) -> bool:
        try:
            self._validate_blob(path, expected_digest, expected_size)
            return True
        except (RecipeNotFoundError, StoreCorruptionError):
            return False

    def _validate_manifest_blobs(self, manifest: TreeManifest) -> None:
        expected_sizes: dict[str, int] = {}
        for entry in manifest.entries:
            expected_sizes.setdefault(entry.sha256, entry.size)
        for digest, size in expected_sizes.items():
            self._validate_blob(self.path_for_blob(digest), digest, size)

    @classmethod
    def _hash_path(cls, path: Path) -> tuple[str, int]:
        digest = hashlib.sha256()
        size = 0
        stream, descriptor_stat = cls._open_source_file(path)
        with stream:
            for chunk in iter(lambda: stream.read(_COPY_CHUNK_SIZE), b""):
                digest.update(chunk)
                size += len(chunk)
        if size != descriptor_stat.st_size:
            raise InvalidRuntimeTreeError(f"runtime file changed while hashing: {path}")
        return digest.hexdigest(), size

    def _validate_blob(self, path: Path, expected_digest: str, expected_size: int) -> None:
        try:
            path_stat = path.lstat()
        except FileNotFoundError as exc:
            raise StoreCorruptionError(f"runtime blob is missing: {expected_digest}") from exc
        if _is_link_or_reparse(path_stat) or not stat.S_ISREG(path_stat.st_mode):
            raise StoreCorruptionError(f"runtime blob is not a regular file: {expected_digest}")
        if path_stat.st_size != expected_size:
            raise StoreCorruptionError(f"runtime blob size mismatch: {expected_digest}")
        try:
            actual_digest, actual_size = self._hash_path(path)
        except InvalidRuntimeTreeError as exc:
            raise StoreCorruptionError(
                f"runtime blob cannot be read safely: {expected_digest}"
            ) from exc
        if actual_size != expected_size or actual_digest != expected_digest:
            raise StoreCorruptionError(f"runtime blob hash mismatch: {expected_digest}")

    def _write_lease(
        self,
        *,
        recipe_digest: str | None,
        tree_digest: str | None,
        blob_digests: tuple[str, ...],
    ) -> _LeaseGuard:
        if (recipe_digest is None) != (tree_digest is None):
            raise RuntimeStoreError(
                "lease recipe_digest and tree_digest must both be set or both be null"
            )
        if recipe_digest is not None:
            _require_digest(recipe_digest, "lease recipe digest")
            _require_digest(tree_digest, "lease tree digest")
        normalized_blobs = tuple(
            sorted({_require_digest(digest, "lease blob digest") for digest in blob_digests})
        )
        if recipe_digest is None and not normalized_blobs:
            raise RuntimeStoreError("blob-only lease must protect at least one blob")
        for _attempt in range(8):
            lease_id = uuid.uuid4().hex
            path = self._active_leases_dir / f"{lease_id}.json"
            lock_path = self._active_leases_dir / f"{lease_id}.lock"
            if (
                path.exists()
                or path.is_symlink()
                or lock_path.exists()
                or lock_path.is_symlink()
            ):
                continue
            lease_lock = _FileLock(lock_path, None)
            lease_lock.__enter__()
            try:
                record = _LeaseRecord(
                    lease_id=lease_id,
                    recipe_digest=recipe_digest,
                    tree_digest=tree_digest,
                    blob_digests=normalized_blobs,
                    created_at=self._now(),
                )
                self._atomic_write(path, _json_file_bytes(record.payload()))
                return _LeaseGuard(self, path, lock_path, lease_lock)
            except Exception:
                lease_lock.__exit__(None, None, None)
                path.unlink(missing_ok=True)
                lock_path.unlink(missing_ok=True)
                RuntimeStore._fsync_directory(self._active_leases_dir)
                raise
        raise RuntimeStoreError("could not allocate a unique runtime lease")

    def _load_recipe_record(self, recipe_digest: str) -> _RecipeRecord:
        path = self.path_for_recipe(recipe_digest)
        if not path.exists() and not path.is_symlink():
            raise RecipeNotFoundError(f"runtime recipe is not stored: {recipe_digest}")
        payload, raw = self._read_json(path, _MAX_RECIPE_RECORD_BYTES, "recipe record")
        record = _RecipeRecord.from_payload(payload, recipe_digest)
        if raw != _json_file_bytes(record.payload()):
            raise StoreCorruptionError(f"recipe record is not canonical: {path}")
        return record

    def _load_tree_manifest(self, tree_digest: str) -> TreeManifest:
        path = self.path_for_tree(tree_digest)
        payload, raw = self._read_json(path, _MAX_MANIFEST_BYTES, "tree manifest")
        manifest = TreeManifest.from_payload(payload)
        if manifest.digest() != tree_digest:
            raise StoreCorruptionError("tree manifest digest does not match its path")
        if raw != _json_file_bytes(manifest.payload()):
            raise StoreCorruptionError(f"tree manifest is not canonical: {path}")
        return manifest

    def _load_lease(self, path: Path) -> _LeaseRecord:
        payload, raw = self._read_json(path, _MAX_LEASE_BYTES, "lease")
        record = _LeaseRecord.from_payload(payload, path.stem)
        if raw != _json_file_bytes(record.payload()):
            raise StoreCorruptionError(f"lease is not canonical: {path}")
        return record

    @staticmethod
    def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise StoreCorruptionError(f"JSON contains duplicate key {key!r}")
            result[key] = value
        return result

    def _read_json(self, path: Path, maximum: int, label: str) -> tuple[Any, bytes]:
        try:
            raw = _read_bounded_regular_file(path, maximum, label)
            payload = json.loads(
                raw,
                object_pairs_hook=self._reject_duplicate_keys,
                parse_constant=lambda value: (_ for _ in ()).throw(
                    StoreCorruptionError(f"{label} contains non-finite number {value}")
                ),
            )
        except StoreCorruptionError:
            raise
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise StoreCorruptionError(f"invalid {label} {path}: {exc}") from exc
        return payload, raw

    def _atomic_write(self, destination: Path, data: bytes) -> None:
        self._ensure_real_directory(destination.parent)
        descriptor, temporary_name = tempfile.mkstemp(prefix="write-", dir=self.tmp_dir)
        temporary = Path(temporary_name)
        try:
            with os.fdopen(descriptor, "wb") as output:
                output.write(data)
                output.flush()
                os.fsync(output.fileno())
            os.chmod(temporary, 0o600)
            try:
                destination_stat = destination.lstat()
            except FileNotFoundError:
                destination_stat = None
            if destination_stat is not None and (
                _is_link_or_reparse(destination_stat)
                or not stat.S_ISREG(destination_stat.st_mode)
            ):
                raise StoreCorruptionError(
                    f"store destination is not a regular file: {destination}"
                )
            os.replace(temporary, destination)
            self._fsync_directory(destination.parent)
        finally:
            temporary.unlink(missing_ok=True)

    @staticmethod
    def _fsync_directory(directory: Path) -> None:
        if os.name == "nt":
            return
        flags = os.O_RDONLY | getattr(os, "O_DIRECTORY", 0)
        try:
            descriptor = os.open(directory, flags)
        except OSError:
            return
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)

    def materialize(
        self,
        recipe: RuntimeRecipe | StoredRuntime | str,
        destination: Path,
    ) -> Path:
        """Copy a validated store tree to a destination that must not exist."""

        destination = Path(destination)
        if destination.exists() or destination.is_symlink():
            raise InvalidRuntimeTreeError(
                f"materialization destination already exists: {destination}"
            )
        destination.parent.mkdir(parents=True, exist_ok=True)
        staging = Path(
            tempfile.mkdtemp(prefix=f".{destination.name}.materializing-", dir=destination.parent)
        )
        try:
            with self.lease(recipe) as stored:
                for entry in stored.manifest.entries:
                    target = staging.joinpath(*PurePosixPath(entry.path).parts)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    blob = self.path_for_blob(entry.sha256)
                    source, source_stat = self._open_source_file(blob)
                    with source, target.open("xb") as output:
                        shutil.copyfileobj(source, output, _COPY_CHUNK_SIZE)
                    if source_stat.st_size != entry.size:
                        raise StoreCorruptionError(
                            f"stored blob changed during materialization: {entry.sha256}"
                        )
                    os.chmod(target, entry.mode)
                    actual_digest, actual_size = self._hash_path(target)
                    if actual_digest != entry.sha256 or actual_size != entry.size:
                        raise StoreCorruptionError(
                            f"materialized file failed verification: {entry.path}"
                        )
            if destination.exists() or destination.is_symlink():
                raise InvalidRuntimeTreeError(
                    f"materialization destination appeared concurrently: {destination}"
                )
            staging.rename(destination)
            return destination
        finally:
            if staging.exists() and not staging.is_symlink():
                shutil.rmtree(staging)
            else:
                staging.unlink(missing_ok=True)

    def gc(
        self,
        *,
        max_age: float | None = None,
        max_bytes: int | None = None,
    ) -> GcResult:
        """Prune unleased recipes by age/LRU, then remove unreachable objects.

        ``max_age`` is measured in seconds from the recipe's last validated
        access. ``max_bytes`` bounds unique reachable blob bytes. A lease is
        live only while its owner holds the paired operating-system lock; GC
        removes valid records whose lock became acquirable after a crash.
        """

        if max_age is not None:
            if isinstance(max_age, bool) or not isinstance(max_age, (int, float)):
                raise ValueError("max_age must be a finite non-negative number or None")
            max_age = float(max_age)
            if not math.isfinite(max_age) or max_age < 0:
                raise ValueError("max_age must be a finite non-negative number or None")
        if max_bytes is not None:
            if isinstance(max_bytes, bool) or not isinstance(max_bytes, int) or max_bytes < 0:
                raise ValueError("max_bytes must be a non-negative integer or None")

        with self._global_lock():
            result = self._gc_locked(max_age=max_age, max_bytes=max_bytes)
        self._record_metrics(
            pruned=result.pruned,
            pruned_trees=result.pruned_trees,
            pruned_blobs=result.pruned_blobs,
            pruned_bytes=result.pruned_bytes,
        )
        return result

    collect_garbage = gc

    def total_blob_bytes(self) -> int:
        """Return physical bytes in the live blob namespace without following links."""

        with self._global_lock():
            return sum(
                path.lstat().st_size
                for path, _digest in self._iter_addressed_files(
                    self._blob_objects_dir, ""
                )
            )

    def _quarantine_builder_staging(
        self, staging: Path, *, global_locked: bool = False
    ) -> Path | None:
        """Retire a private builder directory before any recursive deletion."""

        if not global_locked:
            with self._global_lock():
                return self._quarantine_builder_staging(staging, global_locked=True)

        try:
            staging_stat = staging.lstat()
        except FileNotFoundError:
            return None
        if _is_link_or_reparse(staging_stat) or not stat.S_ISDIR(staging_stat.st_mode):
            raise StoreCorruptionError(f"builder staging path is unsafe: {staging}")
        current_stat = staging.lstat()
        if not _same_identity(staging_stat, current_stat):
            raise StoreCorruptionError(f"builder staging path changed: {staging}")
        destination = self.trash_dir / f"gc-build-{uuid.uuid4().hex}"
        os.replace(staging, destination)
        self._fsync_directory(self.tmp_dir)
        self._fsync_directory(self.trash_dir)
        destination_stat = destination.lstat()
        if not _same_identity(staging_stat, destination_stat):
            raise StoreCorruptionError(
                f"quarantined builder identity changed: {destination}"
            )
        try:
            _remove_tree(destination, destination_stat)
        except OSError:
            # GC retries canonical trash entries. Publication no longer depends on
            # the recursive delete succeeding (notably for Windows read-only files).
            pass
        return destination

    def _cleanup_trash_locked(self) -> None:
        """Best-effort retry of canonical quarantined objects under the GC lock."""

        self._ensure_real_directory(self.trash_dir)
        for path in sorted(self.trash_dir.iterdir()):
            path_stat = path.lstat()
            if _is_link_or_reparse(path_stat):
                raise StoreCorruptionError(f"trash namespace contains unsafe path: {path}")
            match = _TRASH_ENTRY_RE.fullmatch(path.name)
            if match is None:
                raise StoreCorruptionError(f"trash namespace contains unknown path: {path}")
            kind, _nonce = match.groups()
            if kind == "build":
                if not stat.S_ISDIR(path_stat.st_mode):
                    raise StoreCorruptionError(
                        f"builder trash entry is not a real directory: {path}"
                    )
                try:
                    _remove_tree(path, path_stat)
                except OSError:
                    pass
                continue
            if not stat.S_ISREG(path_stat.st_mode):
                raise StoreCorruptionError(
                    f"content trash entry is not a regular file: {path}"
                )
            try:
                _make_writable(path)
                path.unlink()
            except FileNotFoundError:
                pass
            except OSError:
                pass

    def _reap_orphaned_builds_locked(self) -> None:
        """Remove build staging only when its recipe lock is provably abandoned."""

        self._ensure_real_directory(self.tmp_dir)
        by_recipe: dict[str, list[Path]] = {}
        for path in sorted(self.tmp_dir.iterdir()):
            path_stat = path.lstat()
            if _is_link_or_reparse(path_stat):
                raise StoreCorruptionError(f"temporary namespace contains unsafe path: {path}")
            match = _BUILD_STAGING_RE.fullmatch(path.name)
            if match is None:
                continue
            if not stat.S_ISDIR(path_stat.st_mode):
                raise StoreCorruptionError(
                    f"builder staging entry is not a real directory: {path}"
                )
            by_recipe.setdefault(match.group(1), []).append(path)

        for digest, paths in sorted(by_recipe.items()):
            probe = _FileLock(self._locks_dir / f"recipe-{digest}.lock", 0)
            try:
                probe.__enter__()
            except LockTimeoutError:
                continue
            try:
                for path in paths:
                    self._quarantine_builder_staging(path, global_locked=True)
            finally:
                probe.__exit__(None, None, None)

    def _gc_locked(self, *, max_age: float | None, max_bytes: int | None) -> GcResult:
        self._cleanup_trash_locked()
        self._reap_orphaned_builds_locked()
        leases = self._live_leases_locked()
        leased_recipes = {
            lease.recipe_digest for lease in leases if lease.recipe_digest is not None
        }
        leased_trees = {
            lease.tree_digest for lease in leases if lease.tree_digest is not None
        }
        leased_blobs = {digest for lease in leases for digest in lease.blob_digests}

        states: dict[str, _RecipeState] = {}
        corrupt: dict[str, Path] = {}
        for path, digest in self._iter_addressed_files(self.recipes_dir, ".json"):
            try:
                record = self._load_recipe_record(digest)
                manifest = self._load_tree_manifest(record.tree_digest)
                states[digest] = _RecipeState(path, record, manifest)
            except StoreCorruptionError:
                if digest in leased_recipes:
                    raise StoreCorruptionError(
                        f"leased runtime recipe is corrupt; GC stopped before pruning: {digest}"
                    )
                corrupt[digest] = path

        now = self._now()
        selected = set(corrupt)
        if max_age is not None:
            selected.update(
                digest
                for digest, state in states.items()
                if digest not in leased_recipes
                and max(0.0, now - state.record.last_accessed_at) >= max_age
            )

        remaining = {digest: state for digest, state in states.items() if digest not in selected}
        if max_bytes is not None:
            candidates = sorted(
                (
                    state
                    for digest, state in remaining.items()
                    if digest not in leased_recipes
                ),
                key=lambda state: (
                    state.record.last_accessed_at,
                    state.record.created_at,
                    state.record.recipe_digest,
                ),
            )
            for candidate in candidates:
                _trees, blobs = self._reachable(remaining, leased_trees, leased_blobs)
                if self._physical_blob_bytes(blobs) <= max_bytes:
                    break
                digest = candidate.record.recipe_digest
                selected.add(digest)
                remaining.pop(digest, None)

        pruned_recipes = 0
        for digest in sorted(selected):
            state = states.get(digest)
            path = state.path if state is not None else corrupt[digest]
            if self._discard(path, "recipe"):
                pruned_recipes += 1

        reachable_trees, reachable_blobs = self._reachable(
            remaining, leased_trees, leased_blobs
        )
        pruned_trees = 0
        for path, digest in self._iter_addressed_files(self.trees_dir, ".json"):
            if digest not in reachable_trees and self._discard(path, "tree"):
                pruned_trees += 1

        pruned_blobs = 0
        pruned_bytes = 0
        for path, digest in self._iter_addressed_files(self._blob_objects_dir, ""):
            if digest in reachable_blobs:
                continue
            size = path.lstat().st_size
            if self._discard(path, "blob"):
                pruned_blobs += 1
                pruned_bytes += size

        retained_bytes = self._physical_blob_bytes(reachable_blobs)
        self._cleanup_trash_locked()
        return GcResult(
            pruned=pruned_recipes,
            pruned_trees=pruned_trees,
            pruned_blobs=pruned_blobs,
            pruned_bytes=pruned_bytes,
            retained_bytes=retained_bytes,
        )

    @staticmethod
    def _reachable(
        states: Mapping[str, _RecipeState],
        leased_trees: set[str],
        leased_blobs: set[str],
    ) -> tuple[set[str], set[str]]:
        trees = set(leased_trees)
        blobs = set(leased_blobs)
        for state in states.values():
            trees.add(state.record.tree_digest)
            blobs.update(state.manifest.blob_digests)
            blobs.add(state.record.recipe.installer_sha256)
        return trees, blobs

    def _physical_blob_bytes(self, digests: set[str]) -> int:
        total = 0
        for digest in digests:
            path = self.path_for_blob(digest)
            try:
                path_stat = path.lstat()
            except FileNotFoundError:
                continue
            if _is_link_or_reparse(path_stat) or not stat.S_ISREG(path_stat.st_mode):
                raise StoreCorruptionError(f"reachable blob is not a regular file: {digest}")
            total += path_stat.st_size
        return total

    def _lease_namespace_paths(self) -> tuple[dict[str, Path], dict[str, Path]]:
        self._ensure_real_directory(self._active_leases_dir)
        records: dict[str, Path] = {}
        locks: dict[str, Path] = {}
        for path in sorted(self._active_leases_dir.iterdir()):
            path_stat = path.lstat()
            if _is_link_or_reparse(path_stat) or not stat.S_ISREG(path_stat.st_mode):
                raise StoreCorruptionError(f"lease namespace contains unsafe path: {path}")
            match = _LEASE_ENTRY_RE.fullmatch(path.name)
            if match is None:
                raise StoreCorruptionError(f"lease namespace contains unknown file: {path}")
            lease_id, kind = match.groups()
            destination = records if kind == "json" else locks
            if lease_id in destination:  # pragma: no cover - one directory entry per name
                raise StoreCorruptionError(f"lease namespace contains duplicate id: {lease_id}")
            destination[lease_id] = path
        return records, locks

    def _live_leases_locked(self) -> list[_LeaseRecord]:
        """Return locked leases after reaping crash orphans under the global lock."""

        record_paths, lock_paths = self._lease_namespace_paths()
        # Validate every record before changing the namespace. Corrupt lease metadata
        # remains fail-closed even when its process lock appears abandoned.
        records = {
            lease_id: self._load_lease(path)
            for lease_id, path in sorted(record_paths.items())
        }
        live: list[_LeaseRecord] = []
        changed = False
        for lease_id, record in records.items():
            record_path = record_paths[lease_id]
            lock_path = self._active_leases_dir / f"{lease_id}.lock"
            if self._reap_lease_if_unlocked(record_path, lock_path):
                changed = True
            else:
                live.append(record)

        for lease_id in sorted(set(lock_paths) - set(record_paths)):
            lock_path = lock_paths[lease_id]
            if not self._reap_lease_if_unlocked(None, lock_path):
                raise StoreCorruptionError(
                    f"active lease lock has no canonical record: {lock_path}"
                )
            changed = True
        if changed:
            self._fsync_directory(self._active_leases_dir)
        return live

    @staticmethod
    def _reap_lease_if_unlocked(record_path: Path | None, lock_path: Path) -> bool:
        """Return true after removing an orphan, false while another owner is alive."""

        probe = _FileLock(lock_path, 0)
        try:
            probe.__enter__()
        except LockTimeoutError:
            return False
        try:
            if record_path is not None:
                record_path.unlink(missing_ok=True)
        finally:
            probe.__exit__(None, None, None)
            lock_path.unlink(missing_ok=True)
        return True

    def _iter_addressed_files(
        self, directory: Path, suffix: str
    ) -> Iterator[tuple[Path, str]]:
        self._ensure_real_directory(directory)
        for shard in sorted(directory.iterdir()):
            if re.fullmatch(r"[0-9a-f]{2}", shard.name) is None:
                continue
            shard_stat = shard.lstat()
            if _is_link_or_reparse(shard_stat) or not stat.S_ISDIR(shard_stat.st_mode):
                raise StoreCorruptionError(f"content shard is not a real directory: {shard}")
            for path in sorted(shard.iterdir()):
                path_stat = path.lstat()
                if _is_link_or_reparse(path_stat) or not stat.S_ISREG(path_stat.st_mode):
                    raise StoreCorruptionError(f"content object is not a regular file: {path}")
                name = path.name
                if suffix and not name.endswith(suffix):
                    continue
                digest = name[: -len(suffix)] if suffix else name
                if _DIGEST_RE.fullmatch(digest) is None or digest[:2] != shard.name:
                    continue
                yield path, digest

    def _discard(self, path: Path, kind: str) -> bool:
        try:
            path_stat = path.lstat()
        except FileNotFoundError:
            return False
        if (
            kind not in {"recipe", "tree", "blob"}
            or _is_link_or_reparse(path_stat)
            or not stat.S_ISREG(path_stat.st_mode)
        ):
            raise StoreCorruptionError(f"refusing to discard unsafe {kind} path: {path}")
        destination = self.trash_dir / f"gc-{kind}-{uuid.uuid4().hex}"
        os.replace(path, destination)
        self._fsync_directory(path.parent)
        self._fsync_directory(self.trash_dir)
        try:
            _make_writable(destination)
            destination.unlink()
        except FileNotFoundError:
            pass
        except OSError:
            # The live namespace was pruned atomically. Leave the canonical trash
            # entry for a later GC instead of failing the whole collection.
            pass
        return True


__all__ = [
    "GcResult",
    "InvalidRuntimeTreeError",
    "LockTimeoutError",
    "RecipeNotFoundError",
    "RunWorkspace",
    "RuntimeRecipe",
    "RuntimeStore",
    "RuntimeStoreError",
    "STORE_DIRECTORY",
    "STORE_VERSION",
    "StoreCorruptionError",
    "StoreMetrics",
    "StoredRuntime",
    "TreeEntry",
    "TreeManifest",
    "canonical_json_bytes",
]
