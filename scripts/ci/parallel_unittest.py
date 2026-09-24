#!/usr/bin/env python3
"""Run one unittest discovery across worker processes, failing closed wherever a serial run would.

This is ``python -m unittest discover -s START -p PATTERN`` spread over the runner's cores. As
there, the start directory is also the top-level directory, so every test module keeps the
top-level name its siblings import it by, and the working directory stays importable for
``scripts.*`` imports.

A class with class-level fixtures (``setUpClass``/``tearDownClass``) runs whole inside one worker,
so those fixtures keep their meaning; any other class gives each test a fresh instance, so its
tests are scheduled one by one. A worker runs many units in turn, as a serial run does. The
parent discovers once and records how many tests each unit contributes (a class a second module
re-exports is discovered, and therefore run, twice). The run fails unless discovery imported
every module, every unit reports exactly its discovered number of tests, every result is
successful, no worker died, and at least one test ran. The one exception to the count is the
serial runner's own: a class whose ``setUpClass`` raises ``SkipTest`` runs none of its tests
and is reported as skipped.

Each worker gets its own temporary directory, created before any worker starts, so fixtures
that create or inspect files beside their own temporary files never observe another worker's.
"""

from __future__ import annotations

import argparse
import io
import multiprocessing
import os
import shutil
import sys
import tempfile
import time
import unittest
from collections import Counter
from collections.abc import Iterator
from concurrent.futures import ProcessPoolExecutor, as_completed
from contextlib import redirect_stderr, redirect_stdout
from dataclasses import dataclass
from pathlib import Path
from typing import Any

SEPARATOR = "-" * 70
OUTCOMES = (
    ("failures", "failures"),
    ("errors", "errors"),
    ("skipped", "skipped"),
    ("expected_failures", "expected failures"),
    ("unexpected_successes", "unexpected successes"),
)


@dataclass(frozen=True)
class Unit:
    name: str
    tests: int
    repeat: int
    fixture: bool
    weight: tuple[bool, int]


def _tests(suite: unittest.TestSuite) -> Iterator[unittest.TestCase]:
    for item in suite:
        if isinstance(item, unittest.TestSuite):
            yield from _tests(item)
        else:
            yield item


def _has_class_fixture(case: type) -> bool:
    return (
        case.setUpClass.__func__ is not unittest.TestCase.setUpClass.__func__
        or case.tearDownClass.__func__ is not unittest.TestCase.tearDownClass.__func__
    )


def discover(start: Path, *, pattern: str) -> tuple[list[Unit], list[str]]:
    """Return the scheduling units (whole fixture classes, otherwise single tests) and errors."""

    loader = unittest.TestLoader()
    # Like `unittest discover -s`, the absolute start directory is also the top-level directory.
    suite = loader.discover(os.path.abspath(start), pattern=pattern)
    errors = list(loader.errors)
    tests = list(_tests(suite))
    units: list[Unit] = []
    for case, count in Counter(type(test) for test in tests).items():
        if case.__module__ == "unittest.loader":
            errors.append(f"{start}: discovery could not load {case.__qualname__}")
            continue
        name = f"{case.__module__}.{case.__qualname__}"
        per_run = loader.loadTestsFromName(name).countTestCases()
        if per_run <= 0 or count % per_run:
            errors.append(f"{name}: discovered {count} tests, loads {per_run} per run")
            continue
        module = sys.modules[case.__module__]
        source = getattr(module, "__file__", None)
        size = Path(source).stat().st_size if source else 0
        if _has_class_fixture(case):
            units.append(Unit(name, count, count // per_run, True, (True, size)))
            continue
        for test_id, repeat in Counter(test.id() for test in tests if type(test) is case).items():
            units.append(Unit(test_id, repeat, repeat, False, (False, size)))
    return units, errors


def _start_worker(directories: Any, path: list[str]) -> None:
    directory = directories.get()
    os.environ["TMPDIR"] = directory
    tempfile.tempdir = directory
    sys.path[:] = path


def _run_unit(name: str, repeat: int, verbosity: int) -> dict[str, Any]:
    stream = io.StringIO()
    result = unittest.TextTestResult(
        unittest.runner._WritelnDecorator(stream), descriptions=True, verbosity=verbosity
    )
    started = time.perf_counter()
    result.startTestRun()
    with redirect_stdout(stream), redirect_stderr(stream):
        for _ in range(repeat):
            unittest.TestLoader().loadTestsFromName(name).run(result)
    result.stopTestRun()
    result.printErrors()
    class_skips = sum(
        isinstance(test, unittest.suite._ErrorHolder)
        and test.description.startswith("setUpClass ")
        for test, _reason in result.skipped
    )
    return {
        "name": name,
        "tests_run": result.testsRun,
        "successful": result.wasSuccessful(),
        "failures": len(result.failures),
        "errors": len(result.errors),
        "skipped": len(result.skipped),
        "class_skips": class_skips,
        "expected_failures": len(result.expectedFailures),
        "unexpected_successes": len(result.unexpectedSuccesses),
        "seconds": time.perf_counter() - started,
        "output": stream.getvalue(),
    }


def _default_jobs() -> int:
    try:
        return max(1, len(os.sched_getaffinity(0)))
    except AttributeError:
        return max(1, os.cpu_count() or 1)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n", 1)[0])
    parser.add_argument("-s", "--start-directory", type=Path, required=True)
    parser.add_argument("-p", "--pattern", default="test_*.py")
    parser.add_argument("-j", "--jobs", type=int, default=_default_jobs())
    parser.add_argument("-v", "--verbose", action="store_const", const=2, default=1)
    parser.add_argument("--slowest", type=int, default=10, help="units listed in the timing table")
    args = parser.parse_args(argv)
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    if not args.start_directory.is_dir():
        parser.error(f"start directory {args.start_directory} does not exist")

    started = time.perf_counter()
    units, errors = discover(args.start_directory, pattern=args.pattern)
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    expected = sum(unit.tests for unit in units)
    if errors or expected == 0:
        print(f"discovery failed closed: {len(errors)} errors, {expected} tests", file=sys.stderr)
        return 1

    # Fixture classes, then bigger modules, go first so the slow ones do not start last. The
    # order only affects wall time; every unit runs.
    units.sort(key=lambda unit: unit.weight, reverse=True)
    problems: list[str] = []
    totals: Counter[str] = Counter()
    timings: list[tuple[float, str]] = []
    context = multiprocessing.get_context("spawn")
    scratch = Path(tempfile.mkdtemp(prefix="parallel-unittest-"))
    try:
        directories = context.Queue()
        for index in range(args.jobs):
            (scratch / f"worker-{index}").mkdir()
            directories.put(str(scratch / f"worker-{index}"))
        with ProcessPoolExecutor(
            max_workers=args.jobs,
            mp_context=context,
            initializer=_start_worker,
            initargs=(directories, list(sys.path)),
        ) as pool:
            futures = {
                pool.submit(_run_unit, unit.name, unit.repeat, args.verbose): unit
                for unit in units
            }
            for future in as_completed(futures):
                unit = futures[future]
                try:
                    outcome = future.result()
                except Exception as exc:  # a dead worker is a failed run, never a skipped class
                    problems.append(f"{unit.name}: worker failed: {exc!r}")
                    continue
                sys.stdout.write(outcome["output"])
                sys.stdout.flush()
                skipped_class = (
                    unit.fixture
                    and outcome["tests_run"] == 0
                    and outcome["class_skips"] == unit.repeat
                    and outcome["skipped"] == unit.repeat
                )
                if skipped_class:
                    totals["skipped_tests"] += unit.tests
                elif outcome["tests_run"] != unit.tests:
                    problems.append(
                        f"{unit.name}: ran {outcome['tests_run']} tests, discovered {unit.tests}"
                    )
                if not outcome["successful"]:
                    problems.append(f"{unit.name}: unsuccessful")
                for key, _label in OUTCOMES:
                    totals[key] += outcome[key]
                totals["run"] += outcome["tests_run"]
                timings.append((outcome["seconds"], unit.name))
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    elapsed = time.perf_counter() - started

    if totals["run"] + totals["skipped_tests"] != expected:
        problems.append(
            f"ran {totals['run']} tests and skipped {totals['skipped_tests']} with their class, "
            f"discovered {expected}"
        )
    if totals["run"] == 0:
        problems.append("no test ran")
    print(SEPARATOR)
    print(f"{args.start_directory}: {totals['run']} of {expected} discovered tests ran")
    print(f"{args.jobs} workers, {len(units)} scheduling units; slowest:")
    for seconds, name in sorted(timings, reverse=True)[: max(0, args.slowest)]:
        print(f"  {seconds:8.1f}s  {name}")
    print(SEPARATOR)
    print(f"Ran {totals['run']} test{'s' if totals['run'] != 1 else ''} in {elapsed:.3f}s")
    print()
    details = [f"{label}={totals[key]}" for key, label in OUTCOMES if totals[key]]
    suffix = f" ({', '.join(details)})" if details else ""
    if problems:
        for problem in problems:
            print(f"FAILED: {problem}", file=sys.stderr)
        print(f"FAILED{suffix}")
        return 1
    print(f"OK{suffix}")
    return 0


if __name__ == "__main__":
    # Run as a script, this file's directory would lead sys.path. Put the working directory there
    # instead, exactly as `python -m unittest` does, so imports resolve as they do serially.
    sys.path[0] = os.getcwd()
    sys.exit(main())
