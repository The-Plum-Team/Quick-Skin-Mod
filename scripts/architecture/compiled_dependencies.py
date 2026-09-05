#!/usr/bin/env python3
"""Inventory actual internal class dependencies in an assembled, named development JAR.

This is migration diagnostics, not authority for selective E2E: bytecode does not describe
reflection, resource consumption, or runtime wiring. The module/scenario contracts own those.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path

from module_graph import REPOSITORY, load_graph


PREFIX = "com.quickskin.mod."


def strongly_connected(graph: dict[str, set[str]]) -> list[list[str]]:
    indices: dict[str, int] = {}
    low: dict[str, int] = {}
    active: set[str] = set()
    stack: list[str] = []
    groups: list[list[str]] = []

    def visit(node: str) -> None:
        indices[node] = low[node] = len(indices)
        stack.append(node)
        active.add(node)
        for target in sorted(graph.get(node, ())):
            if target not in indices:
                visit(target)
                low[node] = min(low[node], low[target])
            elif target in active:
                low[node] = min(low[node], indices[target])
        if low[node] != indices[node]:
            return
        component: list[str] = []
        while True:
            current = stack.pop()
            active.remove(current)
            component.append(current)
            if current == node:
                break
        if len(component) > 1:
            groups.append(sorted(component))

    for node in sorted(graph):
        if node not in indices:
            visit(node)
    return sorted(groups, key=lambda group: (-len(group), group))


def parse_dependencies(text: str) -> dict[str, set[str]]:
    graph: dict[str, set[str]] = {}
    for line in text.splitlines():
        parts = line.split()
        if len(parts) < 3 or parts[1] != "->" or not parts[0].startswith(PREFIX):
            continue
        source = parts[0].split("$")[0]
        graph.setdefault(source, set())
        if parts[2].startswith(PREFIX):
            target = parts[2].split("$")[0]
            if target != source:
                graph[source].add(target)
    return graph


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repository", type=Path, default=REPOSITORY)
    parser.add_argument("--jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--jdeps", default="jdeps")
    args = parser.parse_args()
    root = args.repository.resolve()
    modules = load_graph(root)
    jar = args.jar.resolve()
    # jdeps' default filter omits same-package references and understates feature cycles.
    command = [args.jdeps, "--multi-release", "base", "--ignore-missing-deps",
               "-verbose:class", "-filter:none", str(jar)]
    result = subprocess.run(command, check=True, capture_output=True, text=True)
    graph = parse_dependencies(result.stdout)
    owners: dict[str, str] = {}
    for module in modules.modules:
        for path in sorted((root / module.path / "src").glob("*/java/**/*.java")):
            if path.relative_to(root / module.path / "src").parts[0] in {"test", "e2e"}:
                continue
            package = re.search(r"^package ([\w.]+);", path.read_text(encoding="utf-8"), re.M)
            if package:
                name = package.group(1) + "." + path.stem
                if name in owners and owners[name] != module.id:
                    raise ValueError(f"class has multiple module owners: {name}")
                owners[name] = module.id
    module_dependencies: dict[str, set[str]] = {module.id: set() for module in modules.modules}
    for source, targets in graph.items():
        for target in targets:
            if source in owners and target in owners and owners[source] != owners[target]:
                module_dependencies[owners[source]].add(owners[target])
    with jar.open("rb") as stream:
        jar_sha256 = hashlib.file_digest(stream, "sha256").hexdigest()
    output = {
        "scope": "Compiled named classes only; reflection/resources require authored runtime coverage.",
        "command": command,
        "jar_sha256": jar_sha256,
        "module_graph_sha256": modules.sha256,
        "class_count": len(graph),
        "edge_count": sum(map(len, graph.values())),
        "cycles": strongly_connected(graph),
        "unowned_classes": sorted(set(graph) - set(owners)),
        "dependencies": {key: sorted(value) for key, value in sorted(graph.items())},
        "module_dependencies": {key: sorted(value) for key, value in sorted(module_dependencies.items())},
        "module_cycles": strongly_connected(module_dependencies),
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2) + "\n", encoding="utf-8")
    args.output.with_suffix(".jdeps.txt").write_text(result.stdout, encoding="utf-8")
    print(f"{output['class_count']} classes; {output['edge_count']} edges; "
          f"class-cycle sizes: {[len(group) for group in output['cycles']]}; "
          f"module cycles: {output['module_cycles']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
