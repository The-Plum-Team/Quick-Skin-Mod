"""Find the pinned mod-base kit for Quick Skin's tests (mod-base SPEC §1.5).

Tests that need the kit call :func:`kit_root`, which resolves and verifies the pinned kit through
the managed bootstrap ``scripts/ci/mod_base_kit.py`` (``kit_path``: the staged overlay,
``MOD_BASE_KIT_PATH`` from the ``setup`` composite, the verified user cache or an anonymous fetch)
and puts ``<kit>/src`` first on ``sys.path``. An unavailable kit raises :class:`RuntimeError`; a
test never skips because of it.

Bytecode is never written into a verified kit: the bootstrap refuses a kit tree holding
``__pycache__`` (kit-digest-v1), and a later ``verify_action_tree`` in the same job would exit 78.
:func:`kit_root` therefore turns bytecode writing off for this process (``sys.dont_write_bytecode``,
set before the bootstrap itself is loaded) and exports ``PYTHONDONTWRITEBYTECODE=1`` so every child
process inherits it; a child started with an explicit environment takes
:func:`child_environment`.
"""

from __future__ import annotations

import importlib.util
import os
import sys
from collections.abc import Mapping, Sequence
from pathlib import Path
from types import ModuleType

REPOSITORY = Path(__file__).resolve().parents[3]
BOOTSTRAP = REPOSITORY / "scripts" / "ci" / "mod_base_kit.py"
#: The private ``sys.modules`` name of the loaded bootstrap (never an importable module name).
BOOTSTRAP_MODULE = "_quick_skin_mod_base_kit_bootstrap"

_resolved: Path | None = None


def _disable_bytecode() -> None:
    sys.dont_write_bytecode = True
    os.environ["PYTHONDONTWRITEBYTECODE"] = "1"


def _bootstrap() -> ModuleType:
    """Load the managed bootstrap by path, without adding ``scripts/ci`` to ``sys.path``."""

    loaded = sys.modules.get(BOOTSTRAP_MODULE)
    if loaded is not None:
        return loaded
    spec = importlib.util.spec_from_file_location(BOOTSTRAP_MODULE, BOOTSTRAP)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load the mod-base bootstrap {BOOTSTRAP}")
    module = importlib.util.module_from_spec(spec)
    # Dataclasses resolve their module through sys.modules while the file executes.
    sys.modules[BOOTSTRAP_MODULE] = module
    try:
        spec.loader.exec_module(module)
    except BaseException:
        sys.modules.pop(BOOTSTRAP_MODULE, None)
        raise
    return module


def kit_root() -> Path:
    """The verified pinned kit root; ``<kit>/src`` is first on ``sys.path`` afterwards.

    Raises :class:`RuntimeError` when the kit cannot be resolved and verified, or when a
    ``mod_base`` package from anywhere else is already imported."""

    global _resolved
    _disable_bytecode()
    if _resolved is None:
        try:
            _resolved = Path(_bootstrap().kit_path(REPOSITORY))
        except Exception as exc:  # noqa: BLE001 - KitError, OSError or a missing bootstrap
            raise RuntimeError(f"the pinned mod-base kit is unavailable: {exc}") from exc
    source = _resolved / "src"
    loaded = sys.modules.get("mod_base")
    if loaded is not None:
        location = getattr(loaded, "__file__", None)
        if not location or Path(location).resolve().parent.parent != source.resolve():
            raise RuntimeError(f"a mod_base package outside the pinned kit is already imported: {location}")
    if str(source) in sys.path:
        sys.path.remove(str(source))
    sys.path.insert(0, str(source))
    return _resolved


def child_environment(environ: Mapping[str, str] | None = None, *,
                      python_path: Sequence[Path | str] = ()) -> dict[str, str]:
    """An environment for a child process that imports the kit: ``environ`` (default: this
    process's) with ``PYTHONDONTWRITEBYTECODE=1`` and ``PYTHONPATH`` set to ``<kit>/src`` followed
    by ``python_path``."""

    root = kit_root()
    environment = dict(os.environ if environ is None else environ)
    environment["PYTHONDONTWRITEBYTECODE"] = "1"
    entries = [str(root / "src"), *(str(entry) for entry in python_path)]
    environment["PYTHONPATH"] = os.pathsep.join(entries)
    return environment
