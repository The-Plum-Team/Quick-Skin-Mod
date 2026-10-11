"""Quick Skin's candidate Build hooks: ``build_target`` and ``run_lane`` of the shared mod-base Build.

Run by ``scripts/ci/mod_base_build_dispatch.py`` inside the kit's disposable candidate account,
from the tested checkout (mod-base ``docs/BUILD-ADAPTER.md``). Both hooks drive the native commands
the legacy workflows run, so the shared and the legacy gates build and test the same way:

* ``build_target`` is the ``target`` job of ``.github/workflows/build-matrix.yml``: the Gradle
  bootstrap checksums, ``build_matrix.py --clean --target T``, ``verify_release.py --target T`` and
  the clean-tree check. It then copies the staged partition into the export under the plan's
  names and removes everything the build left in the checkout.
* ``run_lane`` is ``.github/actions/run-packaged-e2e/action.yml``: the hash-locked launcher
  install, the reverification of the staged bytes, and ``orchestrator.py --packaged`` under
  ``xvfb-run`` with Mesa's software renderer. It copies the action's bounded evidence set into
  the export.

What the account cannot do, the kit does before the host fence: the system packages of the
``xvfb-mesa`` profile (``runtime.system_profile`` of ``scripts/ci/mod-base-build.json``). Nothing
here installs a system package, needs root or a token. Everything a hook writes lives below
``HOME``, ``TMPDIR`` or ``GRADLE_USER_HOME``; the kit fails the step on any other untracked file
outside ``out/mod-base-kit`` and the staged Build, so both hooks clean the checkout before they
return. The Gradle home starts empty (or as the kit's seed): the build downloads what it needs.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import mod_base_build_adapter as adapter

CONFIG_PATH = "scripts/ci/mod-base-build.json"
KIT_OVERLAY = "out/mod-base-kit"
WRAPPER = "gradle/wrapper"
#: The display the native lane gives Minecraft (``action.yml``) and its Mesa software renderer
#: (``on-demand-e2e.yml`` job environment, plus ``SDL_VIDEO_FORCE_EGL`` for 26.x SDL clients).
DISPLAY_ENV = {"LIBGL_ALWAYS_SOFTWARE": "1", "GALLIUM_DRIVER": "llvmpipe", "__GLX_VENDOR_LIBRARY_NAME": "mesa",
               "SDL_VIDEO_FORCE_EGL": "1"}
XVFB_ARGUMENTS = ("--auto-servernum", "--server-args=-screen 0 1920x1080x24 -ac +extension GLX +render")
#: What the native lane uploads from ``e2e-out/current`` (``action.yml``, "Upload bounded packaged
#: evidence"): these top-level files, and below ``profiles/`` every ``result.json`` and every file
#: under a directory of one of the tree names.
EVIDENCE_FILES = ("summary.json", "resolved-matrix.json", "runtime-store.json", "selection.json", "coverage.json")
EVIDENCE_TREES = ("logs", "e2e-report", "screenshots", "crash-reports")
#: The kit's runtime export bounds (mod-base ``model/limits.py``): one lane, and one file by role.
MAX_LANE_FILES = 512
MAX_LANE_BYTES = 256 * 1024 * 1024
MAX_JSON_BYTES = 4 * 1024 * 1024
MAX_PNG_BYTES = 32 * 1024 * 1024
MAX_OTHER_BYTES = 16 * 1024 * 1024
_EXPORT_COMPONENT = re.compile(r"^[A-Za-z0-9_+-](?:(?:[A-Za-z0-9._+-]| (?! )){0,126}[A-Za-z0-9_+-])?$")
_JAVA_VERSION = re.compile(r'^JAVA_VERSION="(?:1\.)?([0-9]+)[^"]*"$', re.MULTILINE)


class CandidateError(adapter.AdapterError):
    """A candidate hook cannot produce the native outputs it owes."""


def _log(message: str) -> None:
    print(f"quick-skin: {message}", flush=True)


def _environment(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise CandidateError(f"{name} is not set")
    return value


def _run(command: list[str], *, cwd: Path, env: dict[str, str], label: str) -> None:
    """Run one native command with its output on the hook's log; a failure ends the hook."""

    _log(f"{label}: {' '.join(command[:4])}{' ...' if len(command) > 4 else ''}")
    started = time.monotonic()
    result = subprocess.run(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL, check=False)
    _log(f"{label}: exit {result.returncode} after {time.monotonic() - started:.0f}s")
    if result.returncode != 0:
        raise CandidateError(f"{label} failed with exit status {result.returncode}")


def _output(command: list[str], *, cwd: Path, env: dict[str, str]) -> str:
    result = subprocess.run(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL, capture_output=True, check=False)
    if result.returncode != 0:
        raise CandidateError(f"{command[0]} {command[1] if len(command) > 1 else ''} failed: "
                             f"{result.stderr.decode('utf-8', 'replace')[-2000:]}")
    return result.stdout.decode("utf-8")


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _config(checkout: Path) -> dict[str, Any]:
    return adapter.decode(adapter.read_regular(checkout / CONFIG_PATH, CONFIG_PATH, max_bytes=1 << 20), CONFIG_PATH)


def _mod(checkout: Path) -> adapter.Mod:
    """The checkout's own native inputs, at the paths its Build config names."""

    config = _config(checkout)
    extra = {entry["name"]: entry["path"] for entry in config["plan_inputs"]}
    return adapter.load_mod(checkout / config["inventory"]["path"], checkout / config["scenario_contract"]["path"],
                            checkout / extra[adapter.PROPERTIES_INPUT])


# -- Toolchains ----------------------------------------------------------------------------------------


def java_homes() -> dict[int, str]:
    """Every JDK home the job admitted (``MB_JAVA_HOMES``) by its feature version, read from the
    home's ``release`` file."""

    homes: dict[int, str] = {}
    for home in _environment("MB_JAVA_HOMES").split(":"):
        release = Path(home) / "release"
        try:
            match = _JAVA_VERSION.search(release.read_text(encoding="utf-8"))
        except OSError as error:
            raise CandidateError(f"JDK home {home} has no readable release file") from error
        if match is None or not (Path(home) / "bin" / "java").is_file():
            raise CandidateError(f"JDK home {home} names no Java version or has no java launcher")
        major = int(match.group(1))
        if major in homes:
            raise CandidateError(f"two admitted JDK homes are Java {major}")
        homes[major] = home
    return homes


def child_environment() -> dict[str, str]:
    """The hook's environment for the native commands it starts. ``PYTHONSAFEPATH`` protects the
    dispatcher's own import roots; the native scripts import their siblings from their own
    directory (``build_matrix.py`` imports ``matrix``), so their interpreters run without it."""

    environment = dict(os.environ)
    for name in ("MB_TARGET_ID", "MB_LANE_ID", "E2E_ROW_JSON", "E2E_SCENARIOS", "PYTHONSAFEPATH"):
        environment.pop(name, None)
    return environment


# -- Checkout hygiene ----------------------------------------------------------------------------------


def tracked_paths(checkout: Path, env: dict[str, str]) -> set[str]:
    listing = _output(["git", "ls-tree", "-r", "-z", "--name-only", "HEAD"], cwd=checkout, env=env)
    return {path for path in listing.split("\0") if path}


def clean_checkout(checkout: Path, env: dict[str, str], *, keep: tuple[str, ...]) -> list[str]:
    """Remove every path of the checkout that the tested commit does not track, except ``.git``
    and the directories in ``keep`` (with what leads to them). Returns what was removed."""

    tracked = tracked_paths(checkout, env)
    directories = {"/".join(path.split("/")[:index]) for path in tracked for index in range(1, path.count("/") + 1)}
    leading = {"/".join(path.split("/")[:index]) for path in keep for index in range(1, path.count("/") + 1)}
    removed: list[str] = []

    def walk(directory: Path, prefix: str) -> None:
        for entry in sorted(os.scandir(directory), key=lambda item: item.name):
            relative = f"{prefix}{entry.name}"
            if relative == ".git" or relative in keep:
                continue
            if entry.is_dir(follow_symlinks=False):
                if relative in directories or relative in leading:
                    walk(Path(entry.path), relative + "/")
                else:
                    shutil.rmtree(entry.path)
                    removed.append(relative + "/")
            elif relative not in tracked:
                os.unlink(entry.path)
                removed.append(relative)

    walk(checkout, "")
    return removed


def require_clean_sources(checkout: Path, env: dict[str, str]) -> None:
    """``git diff --exit-code`` and no tracked change, as the native target job requires, without
    writing the index."""

    _run(["git", "--no-optional-locks", "diff", "--exit-code", "--quiet"], cwd=checkout, env=env,
         label="tracked sources unchanged")
    if _output(["git", "--no-optional-locks", "status", "--porcelain", "--untracked-files=no"], cwd=checkout, env=env):
        raise CandidateError("the build changed tracked sources")


# -- build_target --------------------------------------------------------------------------------------


def verify_gradle_bootstrap(checkout: Path) -> None:
    """The native "Verify exact Gradle bootstrap checksums" step: the wrapper JAR against its
    recorded SHA-256, and the distribution checksum the wrapper enforces against its record."""

    wrapper = checkout / WRAPPER
    line = adapter.read_regular(wrapper / "gradle-wrapper.jar.sha256", "gradle-wrapper.jar.sha256",
                                max_bytes=4096).decode("ascii")
    expected, _, name = line.strip().partition("  ")
    if name != "gradle-wrapper.jar" or _file_sha256(wrapper / "gradle-wrapper.jar") != expected:
        raise CandidateError("gradle-wrapper.jar differs from its recorded SHA-256")
    distributions = sorted(wrapper.glob("gradle-*-bin.zip.sha256"))
    if len(distributions) != 1:
        raise CandidateError("exactly one recorded Gradle distribution checksum is required")
    digest = adapter.read_regular(distributions[0], distributions[0].name, max_bytes=4096).decode("ascii").strip()
    properties = adapter.read_regular(wrapper / "gradle-wrapper.properties", "gradle-wrapper.properties",
                                      max_bytes=65536).decode("utf-8").splitlines()
    if f"distributionSha256Sum={digest}" not in properties:
        raise CandidateError("the Gradle wrapper does not enforce the recorded distribution checksum")


def configure_gradle_home(homes: dict[int, str]) -> None:
    """Hand every admitted JDK to Gradle's toolchain resolution and nothing else: no probing of
    the host, no download. ``--project-cache-dir`` has a property form as well, which keeps the
    project cache out of the checkout while ``build_matrix.py`` runs Gradle with its native
    arguments."""

    gradle_home = Path(_environment("GRADLE_USER_HOME"))
    gradle_home.mkdir(parents=True, exist_ok=True)
    temporary = Path(_environment("TMPDIR"))
    properties = gradle_home / "gradle.properties"
    content = "".join(f"{key}={value}\n" for key, value in (
        ("org.gradle.java.installations.paths", ",".join(homes[major] for major in sorted(homes))),
        ("org.gradle.java.installations.auto-detect", "false"),
        ("org.gradle.java.installations.auto-download", "false"),
        ("org.gradle.projectcachedir", str(temporary / "gradle-project-cache")),
    ))
    if properties.exists():
        raise CandidateError("GRADLE_USER_HOME already has a gradle.properties")
    properties.write_text(content, encoding="utf-8")


def gradle_environment(homes: dict[int, str]) -> dict[str, str]:
    """The native target job's launcher: ``setup-java`` installs 17, 21 and 25 and leaves the last
    one as ``JAVA_HOME``. Stonecutter 0.9.8 refuses a Gradle JVM older than 21."""

    launcher = homes[max(homes)]
    if max(homes) < 21:
        raise CandidateError("Gradle needs an admitted JDK of version 21 or newer")
    environment = child_environment()
    environment["JAVA_HOME"] = launcher
    environment["PATH"] = f"{launcher}/bin:{environment['PATH']}"
    environment["QUICKSKIN_PYTHON"] = sys.executable
    environment["GITHUB_SHA"] = _environment("MB_TESTED_SHA")
    return environment


def build_target(checkout: Path, target: str, export: Path) -> None:
    checkout = checkout.resolve()
    mod = _mod(checkout)
    planned = {item["path"]: item for item in adapter.target_outputs(mod, target)}
    environment = gradle_environment(java_homes())
    head = _output(["git", "rev-parse", "HEAD"], cwd=checkout, env=environment).strip()
    if head != environment["GITHUB_SHA"]:
        raise CandidateError("the checkout is not the tested commit")
    verify_gradle_bootstrap(checkout)
    configure_gradle_home(java_homes())
    python = sys.executable
    _run([python, "scripts/release/build_matrix.py", "--clean", "--target", target], cwd=checkout, env=environment,
         label=f"build_matrix {target}")
    _run([python, "scripts/release/verify_release.py", "--target", target], cwd=checkout, env=environment,
         label=f"verify_release {target}")
    require_clean_sources(checkout, environment)
    stage = checkout / "build" / "release"
    staged = {path.relative_to(stage).as_posix(): path for path in stage.rglob("*") if path.is_file()}
    names = {f"targets/{target}/{relative}" if relative in (adapter.MANIFEST_NAME, adapter.SBOM_NAME) else relative: path
             for relative, path in staged.items()}
    if set(names) != set(planned):
        raise CandidateError(f"the native stage of {target} holds {sorted(names)}, not the planned {sorted(planned)}")
    for relative, source in sorted(names.items()):
        destination = export.joinpath(*relative.split("/"))
        destination.parent.mkdir(parents=True, exist_ok=True)
        with source.open("rb") as reader, open(destination, "xb") as writer:
            shutil.copyfileobj(reader, writer, 1 << 20)
        if _file_sha256(destination) != _file_sha256(source):
            raise CandidateError(f"{relative} changed while it was exported")
        _log(f"exported {relative} ({destination.stat().st_size} bytes)")
    removed = clean_checkout(checkout, environment, keep=(KIT_OVERLAY,))
    _log(f"removed {len(removed)} build paths from the checkout")


# -- run_lane ------------------------------------------------------------------------------------------


def lane_stage(checkout: Path, bundle: str, mod: adapter.Mod, target: str, stage: Path) -> Path:
    """The target's native stage (``artifacts.json`` beside ``files/``, ``harness/`` and ``sbom/``)
    rebuilt from the staged Build under ``TMPDIR``: what ``verify_release.py --target`` staged and
    ``orchestrator.py --target`` consumes. Returns the manifest path."""

    source = checkout / bundle
    for item in adapter.target_outputs(mod, target):
        relative = item["path"]
        native = relative.removeprefix(f"targets/{target}/")
        destination = stage.joinpath(*native.split("/"))
        destination.parent.mkdir(parents=True, exist_ok=True)
        origin = source.joinpath(*relative.split("/"))
        if origin.is_symlink() or not origin.is_file():
            raise CandidateError(f"the staged Build lacks {relative}")
        shutil.copyfile(origin, destination)
    return stage / adapter.MANIFEST_NAME


def reverify_stage(checkout: Path, mod: adapter.Mod, target: str, manifest: Path, *, commit: str) -> None:
    """The native lane's "Reverify staged bytes, matrix, and source commit" step, which is
    ``verify_release.py --target T --verify-staged``: the same calls, for a stage outside the
    checkout (the command insists on one below ``build/``, where the kit stages the Build in its own
    layout)."""

    import artifact_manifest
    import generate_sbom
    import release_identity
    import verify_release

    matrix_path = checkout / adapter.INVENTORY_PATH
    errors = (artifact_manifest.ArtifactManifestError, release_identity.ReleaseIdentityError,
              generate_sbom.SbomError, verify_release.VerificationError, adapter.release_matrix.MatrixError)
    started = time.monotonic()
    try:
        with adapter.bound_contract(mod.contract):
            _reverify(checkout, target, manifest, commit=commit, matrix_path=matrix_path)
    except errors as error:
        raise CandidateError(f"the staged Build of {target} does not verify: {error}") from error
    _log(f"reverified the staged Build of {target} in {time.monotonic() - started:.0f}s")


def _reverify(checkout: Path, target: str, manifest: Path, *, commit: str, matrix_path: Path) -> None:
    """``verify_release.main`` with ``--target`` and ``--verify-staged``, step for step."""

    import artifact_manifest
    import release_identity
    import verify_release

    data = adapter.release_matrix.select_release_target(adapter.release_matrix.load_matrix(matrix_path), target)
    mod_version = verify_release.read_gradle_properties(checkout / "gradle.properties").get(
        data["project"]["mod_version_property"])
    release = release_identity.derive(matrix_path, data, target=target).manifest()
    document = artifact_manifest.load_artifact_manifest(
        manifest, repository=checkout, matrix_path=matrix_path, matrix=data, stage=manifest.parent,
        expected_mod_version=mod_version, expected_commit=commit, expected_release=release)
    verify_release.verify_staged_manifest(checkout, manifest.parent, manifest, document, data, matrix_path,
                                          mod_version, commit, target=target)


def install_launcher(checkout: Path, venv: Path, env: dict[str, str]) -> Path:
    """``pip install --only-binary=:all: --require-hashes -r e2e/requirements.txt`` into a private
    virtual environment: the native lane's launcher install, in the account's own home."""

    _run([sys.executable, "-m", "venv", str(venv)], cwd=checkout, env=env, label="create launcher venv")
    python = venv / "bin" / "python"
    _run([str(python), "-m", "pip", "install", "--no-input", "--disable-pip-version-check", "--only-binary=:all:",
          "--require-hashes", "--requirement", "e2e/requirements.txt"], cwd=checkout, env=env,
         label="install hash-locked launcher")
    return python


def _evidence(current: Path) -> list[str]:
    """The native upload set below ``e2e-out/current``, by relative path."""

    selected = []
    for path in sorted(current.rglob("*")):
        if path.is_symlink() or not (path.is_file() or path.is_dir()):
            raise CandidateError(f"evidence holds a link or special file: {path.name}")
        if not path.is_file():
            continue
        parts = path.relative_to(current).parts
        if len(parts) == 1 and parts[0] in EVIDENCE_FILES:
            selected.append(parts[0])
        elif parts[0] == "profiles" and (parts[-1] == "result.json"
                                         or any(part in EVIDENCE_TREES for part in parts[1:-1])):
            selected.append("/".join(parts))
    return selected


def export_evidence(current: Path, export: Path, lane: str) -> tuple[int, int]:
    """Copy the evidence set to ``export/lanes/<lane>/`` within the kit's runtime export bounds."""

    selected = _evidence(current)
    if not selected or len(selected) > MAX_LANE_FILES:
        raise CandidateError(f"the lane evidence holds {len(selected)} files (1..{MAX_LANE_FILES} allowed)")
    total = 0
    for relative in selected:
        exported = f"lanes/{lane}/{relative}"
        if len(exported) > 300 or exported.count("/") >= 16 or not all(
                _EXPORT_COMPONENT.fullmatch(part) for part in exported.split("/")):
            raise CandidateError(f"evidence path {relative!r} is not an export path")
        source = current.joinpath(*relative.split("/"))
        size = source.stat().st_size
        directories = relative.split("/")[:-1]
        cap, empty = ((MAX_OTHER_BYTES, True) if "crash-reports" in directories
                      else (MAX_PNG_BYTES, False) if relative.endswith(".png")
                      else (MAX_JSON_BYTES, False) if relative.endswith(".json") else (MAX_OTHER_BYTES, True))
        if size > cap or (size == 0 and not empty):
            raise CandidateError(f"evidence file {relative} has {size} bytes, outside its role's bounds")
        total += size
        if total > MAX_LANE_BYTES:
            raise CandidateError("the lane evidence exceeds 256 MiB")
        destination = export.joinpath(*exported.split("/"))
        destination.parent.mkdir(parents=True, exist_ok=True)
        with source.open("rb") as reader, open(destination, "xb") as writer:
            shutil.copyfileobj(reader, writer, 1 << 20)
    return len(selected), total


def _report_results(summary: Path) -> None:
    try:
        results = json.loads(summary.read_text(encoding="utf-8"))["results"]
        for result in results:
            _log(f"  {result.get('scenario')}: {result.get('status')} in {result.get('elapsed_s')}s"
                 + (f" ({str(result.get('error'))[:500]})" if result.get("error") else ""))
    except (OSError, ValueError, KeyError, TypeError) as error:
        _log(f"  summary.json is unreadable: {error}")


def run_lane(checkout: Path, lane: str, export: Path) -> None:
    checkout = checkout.resolve()
    config = _config(checkout)
    mod = _mod(checkout)
    row = adapter.decode(_environment("E2E_ROW_JSON").encode("utf-8"), "E2E_ROW_JSON")
    expected = adapter.runtime_values(mod, lane)
    if type(row) is not dict or row.get("artifact_node") != lane or _environment("E2E_SCENARIOS") != expected[
            "E2E_SCENARIOS"] or row != json.loads(expected["E2E_ROW_JSON"]):
        raise CandidateError(f"the runtime values do not describe lane {lane} of this checkout")
    target = adapter.lane_target(mod, lane)
    home = Path(_environment("HOME"))
    temporary = Path(_environment("TMPDIR"))
    environment = child_environment()
    head = _output(["git", "rev-parse", "HEAD"], cwd=checkout, env=environment).strip()
    if head != _environment("MB_TESTED_SHA"):
        raise CandidateError("the checkout is not the tested commit")
    for tool in ("xvfb-run", "Xvfb", "xauth"):
        if shutil.which(tool, path=environment["PATH"]) is None:
            raise CandidateError(f"{tool} is missing: the job must install the xvfb-mesa system profile")
    homes = java_homes()
    for major, java_home in homes.items():
        environment[f"QUICKSKIN_JAVA_{major}"] = java_home
    environment.update(DISPLAY_ENV)
    environment["QUICKSKIN_E2E_RUNTIME_STORE"] = str(temporary / "quick-skin-runtime-store")
    environment["QUICKSKIN_E2E_SERVER_STORE"] = str(temporary / "quick-skin-server-store")
    environment["PIP_CONFIG_FILE"] = os.devnull
    environment["GITHUB_SHA"] = head
    python = install_launcher(checkout, home / "e2e-venv", environment)
    manifest = lane_stage(checkout, config["bundle"]["path"], mod, target, temporary / f"stage-{target}")
    reverify_stage(checkout, mod, target, manifest, commit=head)
    output_root = home / "e2e-out"
    command = ["xvfb-run", *XVFB_ARGUMENTS, str(python), "e2e/orchestrator.py", "--matrix", adapter.INVENTORY_PATH,
               "--target", target, "--row-json", _environment("E2E_ROW_JSON"), "--artifacts-manifest", str(manifest),
               "--packaged", "--scenarios", _environment("E2E_SCENARIOS"), "--output-root", str(output_root)]
    _log(f"run {lane} on target {target}: {_environment('E2E_SCENARIOS')}")
    started = time.monotonic()
    result = subprocess.run(command, cwd=checkout, env=environment, stdin=subprocess.DEVNULL, check=False)
    _log(f"orchestrator exit {result.returncode} after {time.monotonic() - started:.0f}s")
    current = output_root / "current"
    if (current / "summary.json").is_file():
        _report_results(current / "summary.json")
    if result.returncode != 0:
        raise CandidateError(f"packaged E2E of {lane} failed with exit status {result.returncode}")
    count, size = export_evidence(current, export, lane)
    _log(f"exported {count} evidence files, {size} bytes")
    removed = clean_checkout(checkout, environment, keep=(KIT_OVERLAY, config["bundle"]["path"]))
    _log(f"removed {len(removed)} runtime paths from the checkout")
