#!/usr/bin/env python3
"""Validate the module definitions shared by Gradle and change-impact planning.

The graph describes declared boundaries, not proof of runtime independence. Separate
compilation enforces Java dependencies; E2E coverage must additionally declare runtime
integration and resource relationships before a graph can authorize selective execution.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


REPOSITORY = Path(__file__).resolve().parents[2]
GRAPH_PATH = "architecture/modules.json"
MAX_GRAPH_BYTES = 256 * 1024
MAX_MODULES = 256
IDENTIFIER = re.compile(r"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
DEPENDENCY_KINDS = ("api", "implementation", "runtime_only")
LIBRARY_SCOPES = ("api", "implementation", "compile_only_api", "compile_only",
                  "runtime_only", "test_implementation", "test_runtime_only")
COORDINATE = re.compile(r"^[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+$")
MODULE_KEYS = {"id", "path", "kind", "environment", "libraries", *DEPENDENCY_KINDS}


class ModuleGraphError(ValueError):
    """A module inventory cannot be used to compile or classify changes safely."""


def repository_path(value: Any) -> str:
    if not isinstance(value, str) or not value or any(ord(c) < 32 for c in value):
        raise ModuleGraphError("module paths must be non-empty printable strings")
    path = PurePosixPath(value)
    if (
        path.is_absolute()
        or "\\" in value
        or ":" in value
        or any(part in {".", ".."} for part in path.parts)
        or path.as_posix() != value
        or value == "."
    ):
        raise ModuleGraphError(f"path must be canonical and repository-relative: {value!r}")
    return value


def unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ModuleGraphError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


@dataclass(frozen=True)
class Module:
    id: str
    path: str
    kind: str
    environment: str
    api: tuple[str, ...]
    implementation: tuple[str, ...]
    runtime_only: tuple[str, ...]
    libraries: tuple[tuple[str, tuple[str, ...]], ...]

    @property
    def compile_dependencies(self) -> tuple[str, ...]:
        return self.api + self.implementation

    @property
    def dependencies(self) -> tuple[str, ...]:
        return self.compile_dependencies + self.runtime_only

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "path": self.path,
            "kind": self.kind,
            "environment": self.environment,
            "libraries": {scope: list(ids) for scope, ids in self.libraries},
            **{kind: list(getattr(self, kind)) for kind in DEPENDENCY_KINDS},
        }


class ModuleGraph:
    def __init__(self, modules: tuple[Module, ...], libraries: dict[str, str], sha256: str):
        self.modules = modules
        self.libraries = libraries
        self.sha256 = sha256
        self.by_id = {module.id: module for module in modules}
        self.order = self._dependency_order()

    def _dependency_order(self) -> tuple[str, ...]:
        completed: set[str] = set()
        visiting: list[str] = []
        order: list[str] = []

        def visit(module_id: str) -> None:
            if module_id in visiting:
                raise ModuleGraphError("module dependency cycle: " + " -> ".join(
                    visiting[visiting.index(module_id):] + [module_id]
                ))
            if module_id in completed:
                return
            visiting.append(module_id)
            for dependency in sorted(self.by_id[module_id].dependencies):
                visit(dependency)
            visiting.pop()
            completed.add(module_id)
            order.append(module_id)

        for module_id in sorted(self.by_id):
            visit(module_id)
        return tuple(order)

    def owner(self, path: str) -> str | None:
        normalized = repository_path(path)
        return next((module.id for module in self.modules
                     if normalized.startswith(module.path + "/")), None)

    def affected_modules(self, changed_paths: Iterable[str]) -> tuple[str, ...] | None:
        """Reverse dependency closure, or None when any ownership is unproven.

        None requires the caller to choose full coverage. An empty or partly unknown
        change must never become an empty/partial successful selection.
        """
        owners = {self.owner(path) for path in changed_paths}
        if not owners or None in owners:
            return None
        affected = {owner for owner in owners if owner is not None}
        for module_id in self.order:
            if affected.intersection(self.by_id[module_id].dependencies):
                affected.add(module_id)
        return tuple(sorted(affected))

    def dependencies_of(self, module_id: str) -> tuple[str, ...]:
        if module_id not in self.by_id:
            raise ModuleGraphError(f"unknown module: {module_id}")
        result: set[str] = set()

        def visit(current: str) -> None:
            for dependency in self.by_id[current].dependencies:
                if dependency not in result:
                    result.add(dependency)
                    visit(dependency)

        visit(module_id)
        return tuple(item for item in self.order if item in result)

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": 1,
            "sha256": self.sha256,
            "libraries": self.libraries,
            "modules": [self.by_id[item].to_dict() for item in self.order],
        }


def parse_graph(raw: bytes) -> ModuleGraph:
    if not raw or len(raw) > MAX_GRAPH_BYTES:
        raise ModuleGraphError("module graph is empty or exceeds the byte limit")
    try:
        data = json.loads(raw.decode("utf-8"), object_pairs_hook=unique_object)
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise ModuleGraphError("module graph must be bounded UTF-8 JSON") from error
    if not isinstance(data, dict) or set(data) != {"schema_version", "libraries", "modules"}:
        raise ModuleGraphError("module graph must define schema_version, libraries and modules only")
    if type(data["schema_version"]) is not int or data["schema_version"] != 1:
        raise ModuleGraphError("unsupported module graph schema_version")
    rows = data["modules"]
    if not isinstance(rows, list) or not 1 <= len(rows) <= MAX_MODULES:
        raise ModuleGraphError("module inventory must be a non-empty bounded list")
    libraries = data["libraries"]
    if not isinstance(libraries, dict) or len(libraries) > MAX_MODULES:
        raise ModuleGraphError("library inventory must be a bounded object")
    coordinates: set[str] = set()
    for library_id, coordinate in libraries.items():
        if not IDENTIFIER.fullmatch(library_id):
            raise ModuleGraphError("invalid library id")
        if (not isinstance(coordinate, str) or not COORDINATE.fullmatch(coordinate)
                or coordinate.upper().endswith("-SNAPSHOT")
                or coordinate.rsplit(":", 1)[1].lower() in {"latest", "release"}):
            raise ModuleGraphError(f"library must pin a release coordinate: {library_id}")
        identity = coordinate.rsplit(":", 1)[0]
        if identity in coordinates:
            raise ModuleGraphError(f"duplicate library coordinate: {identity}")
        coordinates.add(identity)
    modules: dict[str, Module] = {}
    for row in rows:
        if not isinstance(row, dict) or set(row) != MODULE_KEYS:
            raise ModuleGraphError("module fields must exactly match the schema")
        module_id = row["id"]
        if not isinstance(module_id, str) or not IDENTIFIER.fullmatch(module_id):
            raise ModuleGraphError("invalid module id")
        if module_id in modules:
            raise ModuleGraphError(f"duplicate module id: {module_id}")
        path = repository_path(row["path"])
        if row["kind"] not in ("java-library", "minecraft"):
            raise ModuleGraphError(f"unknown module kind: {module_id}")
        if row["environment"] not in ("common", "client", "server", "mixed"):
            raise ModuleGraphError(f"unknown module environment: {module_id}")
        library_scopes = row["libraries"]
        if not isinstance(library_scopes, dict) or set(library_scopes) - set(LIBRARY_SCOPES):
            raise ModuleGraphError(f"invalid library scopes: {module_id}")
        production_libraries: set[str] = set()
        for scope, ids in library_scopes.items():
            if not isinstance(ids, list) or len(ids) > MAX_MODULES:
                raise ModuleGraphError(f"{module_id}.libraries.{scope} must be a bounded list")
            seen_libraries: set[str] = set()
            for library_id in ids:
                if not isinstance(library_id, str) or library_id not in libraries:
                    raise ModuleGraphError(f"unknown library: {module_id}.{scope}")
                if library_id in seen_libraries or (
                    not scope.startswith("test_") and library_id in production_libraries
                ):
                    raise ModuleGraphError(f"duplicate library dependency: {module_id}.{scope}")
                seen_libraries.add(library_id)
            if not scope.startswith("test_"):
                production_libraries.update(seen_libraries)
        dependencies: dict[str, tuple[str, ...]] = {}
        seen: set[str] = set()
        for kind in DEPENDENCY_KINDS:
            values = row[kind]
            if not isinstance(values, list) or len(values) > MAX_MODULES:
                raise ModuleGraphError(f"{module_id}.{kind} must be a bounded list")
            for value in values:
                if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
                    raise ModuleGraphError(f"invalid dependency in {module_id}.{kind}")
                if value == module_id or value in seen:
                    raise ModuleGraphError(f"self or duplicate dependency: {module_id} -> {value}")
                seen.add(value)
            dependencies[kind] = tuple(sorted(values))
        modules[module_id] = Module(module_id, path, row["kind"], row["environment"],
                                    libraries=tuple((scope, tuple(sorted(ids)))
                                                    for scope, ids in sorted(library_scopes.items())),
                                    **dependencies)
    for module in modules.values():
        for dependency_id in module.dependencies:
            dependency = modules.get(dependency_id)
            if dependency is None:
                raise ModuleGraphError(f"unknown dependency: {module.id} -> {dependency_id}")
            if module.environment != "mixed" and dependency.environment not in (
                "common", module.environment,
            ):
                raise ModuleGraphError(f"environment leak: {module.id} -> {dependency_id}")
            if module.kind == "java-library" and dependency.kind != "java-library":
                raise ModuleGraphError(f"Minecraft dependency in Java library: {module.id}")
        for other in modules.values():
            if module.id != other.id and (
                module.path == other.path or module.path.startswith(other.path + "/")
            ):
                raise ModuleGraphError(f"overlapping module paths: {module.id}, {other.id}")
    return ModuleGraph(tuple(modules[key] for key in sorted(modules)), dict(sorted(libraries.items())),
                       hashlib.sha256(raw).hexdigest())


def load_graph(repository: Path = REPOSITORY) -> ModuleGraph:
    root = repository.resolve()
    path = root / GRAPH_PATH
    if path.is_symlink() or path.resolve() != path:
        raise ModuleGraphError("module graph must not resolve through a symbolic link")
    try:
        with path.open("rb") as stream:
            graph = parse_graph(stream.read(MAX_GRAPH_BYTES + 1))
    except OSError as error:
        raise ModuleGraphError(f"cannot read module graph: {error}") from error
    for module in graph.modules:
        directory = root / module.path
        if not directory.is_dir() or directory.resolve() != directory:
            raise ModuleGraphError(f"missing or linked module directory: {module.path}")
    return graph


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=REPOSITORY)
    args = parser.parse_args(argv)
    try:
        graph = load_graph(args.repository)
    except ModuleGraphError as error:
        print(f"Module graph validation failed: {error}", file=sys.stderr)
        return 2
    print(json.dumps(graph.to_dict(), sort_keys=True, separators=(",", ":")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
