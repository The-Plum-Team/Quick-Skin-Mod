from __future__ import annotations

import contextlib
import io
import sys
import tempfile
import textwrap
import unittest
import uuid
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

import parallel_unittest  # noqa: E402

PASSING = """
import unittest

class Fixture(unittest.TestCase):
    calls = 0

    @classmethod
    def setUpClass(cls):
        Fixture.calls += 1

    def test_class_fixture_runs_once_per_class(self):
        self.assertEqual(1, Fixture.calls)

    def test_second(self):
        self.assertEqual(1, Fixture.calls)

@unittest.skip("explicitly skipped")
class Skipped(unittest.TestCase):
    def test_skipped(self):
        raise AssertionError("must not run")
"""


class ParallelUnittestTest(unittest.TestCase):
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        # Discovery imports fixture modules by top-level name into this process, as
        # `unittest discover -s` does, so every test uses fresh names and restores the path.
        self.start = Path(temporary.name) / "tests"
        self.start.mkdir()
        self.prefix = f"test_parallel_{uuid.uuid4().hex}_"
        path = list(sys.path)
        self.addCleanup(sys.path.__setitem__, slice(None), path)
        self.addCleanup(self.forget_modules)

    def forget_modules(self) -> None:
        for name in [name for name in sys.modules if name.startswith(self.prefix)]:
            del sys.modules[name]

    def module(self, name: str, source: str) -> str:
        module = self.prefix + name
        (self.start / f"{module}.py").write_text(textwrap.dedent(source))
        return module

    def run_main(self) -> tuple[int, str, str]:
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            code = parallel_unittest.main(["-s", str(self.start), "-p", "test_*.py", "-j", "2", "-v"])
        return code, stdout.getvalue(), stderr.getvalue()

    def serial_count(self) -> int:
        # Exactly the serial CI mode: no top-level directory besides the start directory.
        suite = unittest.TestLoader().discover(str(self.start), pattern="test_*.py")
        result = unittest.TestResult()
        suite.run(result)
        return result.testsRun

    def test_passing_classes_and_skips_report_the_serial_count(self) -> None:
        self.module("one", PASSING)
        self.module("two", "import unittest\nclass B(unittest.TestCase):\n    def test_b(self): pass\n")
        code, stdout, _ = self.run_main()
        self.assertEqual(0, code, stdout)
        self.assertEqual(4, self.serial_count())
        self.assertIn("Ran 4 tests in ", stdout)
        self.assertIn("OK (skipped=1)", stdout)
        self.assertIn("test_class_fixture_runs_once_per_class", stdout)
        self.assertIn(f"{self.start}: 4 of 4 discovered tests ran", stdout)

    def test_modules_import_siblings_by_their_top_level_names(self) -> None:
        helper = self.module("helper", "import unittest\nVALUE = 7\n")
        self.module("user", (
            f"import unittest\nfrom {helper} import VALUE\n"
            "class User(unittest.TestCase):\n    def test_value(self): self.assertEqual(7, VALUE)\n"
        ))
        code, stdout, _ = self.run_main()
        self.assertEqual(0, code, stdout)
        self.assertIn("Ran 1 test in ", stdout)

    def test_a_reexported_class_runs_as_often_as_discovery_finds_it(self) -> None:
        one = self.module("one", PASSING)
        self.module("reexport", f"from {one} import Fixture\n")
        code, stdout, _ = self.run_main()
        self.assertEqual(0, code, stdout)
        self.assertEqual(5, self.serial_count())
        self.assertIn("Ran 5 tests in ", stdout)

    def test_a_class_skipped_by_its_fixture_is_reported_as_the_serial_run_reports_it(self) -> None:
        self.module("one", PASSING)
        self.module("skip", (
            "import unittest\nclass Unavailable(unittest.TestCase):\n"
            "    @classmethod\n    def setUpClass(cls): raise unittest.SkipTest('needs a tool')\n"
            "    def test_a(self): raise AssertionError('must not run')\n"
            "    def test_b(self): raise AssertionError('must not run')\n"
        ))
        code, stdout, _ = self.run_main()
        self.assertEqual(0, code, stdout)
        self.assertEqual(3, self.serial_count())
        self.assertIn("Ran 3 tests in ", stdout)
        self.assertIn("OK (skipped=2)", stdout)

    def test_every_kind_of_unsuccessful_run_fails_closed(self) -> None:
        # label -> (module source, the reason the run must report)
        cases = {
            "failure": (
                "import unittest\nclass A(unittest.TestCase):\n    def test_a(self): self.fail('x')\n",
                "bad.A.test_a: unsuccessful",
            ),
            "error": (
                "import unittest\nclass A(unittest.TestCase):\n    def test_a(self): raise RuntimeError('x')\n",
                "bad.A.test_a: unsuccessful",
            ),
            "class fixture": ((
                "import unittest\nclass A(unittest.TestCase):\n"
                "    @classmethod\n    def setUpClass(cls): raise RuntimeError('x')\n"
                "    def test_a(self): pass\n"
            ), "bad.A: ran 0 tests, discovered 1"),
            "class fixture skip beside an error": ((
                "import unittest\nclass A(unittest.TestCase):\n"
                "    @classmethod\n    def setUpClass(cls):\n"
                "        cls.addClassCleanup(lambda: 1 / 0)\n"
                "        raise unittest.SkipTest('x')\n"
                "    def test_a(self): pass\n"
            ), "bad.A: unsuccessful"),
            "unexpected success": ((
                "import unittest\nclass A(unittest.TestCase):\n"
                "    @unittest.expectedFailure\n    def test_a(self): pass\n"
            ), "bad.A.test_a: unsuccessful"),
            "import": ("import does_not_exist_anywhere\n", "discovery failed closed"),
            "dead worker": (
                "import os, unittest\nclass A(unittest.TestCase):\n    def test_a(self): os._exit(0)\n",
                "worker failed",
            ),
        }
        for label, (source, reason) in cases.items():
            with self.subTest(label=label):
                self.setUp()
                self.module("one", PASSING)
                self.module("bad", source)
                code, stdout, stderr = self.run_main()
                self.assertEqual(1, code, stdout + stderr)
                self.assertIn(reason, stderr)
                self.assertNotIn("\nOK", stdout)

    def test_only_classes_without_class_fixtures_are_split_into_single_tests(self) -> None:
        self.module("one", PASSING)
        self.module("two", (
            "import unittest\nclass Plain(unittest.TestCase):\n"
            "    def test_a(self): pass\n    def test_b(self): pass\n"
            "class Torn(unittest.TestCase):\n"
            "    @classmethod\n    def tearDownClass(cls): pass\n"
            "    def test_c(self): pass\n    def test_d(self): pass\n"
        ))
        units, errors = parallel_unittest.discover(self.start, pattern="test_*.py")
        self.assertEqual([], errors)
        names = {unit.name.removeprefix(self.prefix): unit.tests for unit in units}
        self.assertEqual({
            "one.Fixture": 2,
            "one.Skipped.test_skipped": 1,
            "two.Plain.test_a": 1,
            "two.Plain.test_b": 1,
            "two.Torn": 2,
        }, names)
        code, stdout, _ = self.run_main()
        self.assertEqual(0, code, stdout)
        self.assertIn(f"Ran {self.serial_count()} tests in ", stdout)

    def test_each_worker_has_a_private_temporary_directory(self) -> None:
        self.module("tmp", (
            "import os, subprocess, sys, tempfile, unittest\n"
            "class Private(unittest.TestCase):\n"
            "    def test_python_and_children_share_the_worker_directory(self):\n"
            "        directory = tempfile.gettempdir()\n"
            "        self.assertTrue(os.path.basename(directory).startswith('worker-'), directory)\n"
            "        self.assertTrue(os.path.basename(os.path.dirname(directory)).startswith('parallel-unittest-'))\n"
            "        child = subprocess.run([sys.executable, '-c', 'import tempfile; print(tempfile.gettempdir())'],\n"
            "                               capture_output=True, text=True, check=True)\n"
            "        self.assertEqual(directory, child.stdout.strip())\n"
        ))
        code, stdout, _ = self.run_main()
        self.assertEqual(0, code, stdout)
        self.assertIn("Ran 1 test in ", stdout)

    def test_no_tests_is_a_failure(self) -> None:
        code, _, stderr = self.run_main()
        self.assertEqual(1, code)
        self.assertIn("0 tests", stderr)


if __name__ == "__main__":
    unittest.main()
