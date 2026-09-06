from __future__ import annotations

import copy
import io
import json
import sys
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts/release"))
import matrix
import release_sources


class ReleaseSourcesTest(unittest.TestCase):
    def setUp(self):
        self.path = ROOT / "release/release-matrix.json"
        self.data = matrix.load_matrix(self.path)
        self.names = ["master", "forge-and-fabric-1.20.1", "fabric-and-neoforge-1.21.8",
                      "feature/new-menu", "automation/sync/fabric-and-neoforge-1.21.8/old"]

    def test_shared_layout_uses_only_its_source_branch_and_never_ports_old_refs(self):
        self.assertEqual("shared", release_sources.source_mode(self.data))
        self.assertEqual(["master"], release_sources.source_branches(self.data, self.names))
        for requested in (None, "forge-and-fabric-1.20.1", "unsupported"):
            with self.subTest(requested=requested):
                self.assertEqual([], release_sources.version_port_targets(self.data, self.names, requested=requested))

    def test_corrupt_matrix_cannot_be_classified_as_retired(self):
        invalid = copy.deepcopy(self.data)
        invalid["artifacts"][-1]["java"] = 0
        for operation in (release_sources.source_mode, release_sources.source_branches,
                          release_sources.version_port_targets):
            with self.subTest(operation=operation.__name__), self.assertRaises(matrix.MatrixError):
                operation(invalid)

    def test_historical_matrix_keeps_explicit_branch_layout_recovery(self):
        legacy = json.loads((Path(__file__).parent / "fixtures/legacy-schema2-matrix.json").read_bytes())
        self.assertEqual("version-branches", release_sources.source_mode(legacy))
        self.assertEqual(["fabric-and-neoforge-1.21.8", "forge-and-fabric-1.20.1"],
                         release_sources.version_port_targets(legacy, self.names))
        self.assertEqual(["forge-and-fabric-1.20.1"], release_sources.version_port_targets(
            legacy, self.names, requested="forge-and-fabric-1.20.1"))
        with self.assertRaises(matrix.MatrixError):
            release_sources.version_port_targets(legacy, self.names, requested="fabric-99.0")

    def test_shared_cli_does_not_read_stdin_for_mode_branches_or_ports(self):
        for kind, expected in (("mode", "shared"), ("branches", '["master"]'), ("version-ports", "[]")):
            with self.subTest(kind=kind), redirect_stdout(io.StringIO()) as output, patch.object(sys, "stdin", None):
                self.assertEqual(0, release_sources.main(["--matrix", str(self.path), "--kind", kind]))
                self.assertEqual(expected, output.getvalue().strip())
