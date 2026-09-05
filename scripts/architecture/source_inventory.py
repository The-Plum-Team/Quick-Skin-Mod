"""Resolve production/test sources through the module registry, independent of their owner folder."""

from __future__ import annotations

from pathlib import Path

from .module_graph import REPOSITORY, ModuleGraphError, load_graph, repository_path


def _roots(repository: Path) -> tuple[Path, ...]:
    return tuple(repository / module.path / "src" for module in load_graph(repository).modules)


def java_sources(*, source_set: str = "main", repository: Path = REPOSITORY) -> tuple[Path, ...]:
    """Enumerate one authored source set across all registered modules, excluding generated output."""
    if "/" in source_set:
        raise ModuleGraphError(f"invalid Java source set: {source_set!r}")
    repository_path(source_set)
    paths = tuple(sorted(path for root in _roots(repository.resolve())
                         for path in (root / source_set / "java").rglob("*.java")))
    if any(path.resolve() != path or path.is_symlink() for path in paths):
        raise ModuleGraphError("Java sources must not resolve through symbolic links")
    return paths


def java_source(relative: str, *, source_set: str = "main", repository: Path = REPOSITORY) -> Path:
    """Find one authored source under com.quickskin.mod; missing/ambiguous ownership is an error.

    Overlay selection remains explicit: policy checks must identify whether they inspect the
    canonical implementation or a matrix-routed API-family replacement.
    """
    relative = repository_path(relative)
    if "/" in source_set or source_set not in {"main", "test", "e2e"} and not source_set.startswith("legacy"):
        raise ModuleGraphError(f"invalid Java source set: {source_set!r}")
    repository_path(source_set)
    candidates = tuple(root / source_set / "java/com/quickskin/mod" / relative
                       for root in _roots(repository.resolve()))
    matches = tuple(path for path in candidates if path.is_file())
    if len(matches) != 1:
        raise ModuleGraphError(f"expected one owner for {source_set}/{relative}; found {len(matches)}")
    path = matches[0]
    if path.resolve() != path or path.is_symlink():
        raise ModuleGraphError(f"Java source must not resolve through a symbolic link: {path}")
    return path
