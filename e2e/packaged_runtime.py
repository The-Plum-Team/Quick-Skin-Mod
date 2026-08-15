"""Packaged-jar Minecraft runtime used by the Phase 3 release gate.

This module deliberately launches production loader installations.  It never
adds a Gradle source set, a Loom development output, or a remapped cache copy of
Quick Skin to the game classpath.
"""

from __future__ import annotations

import hashlib
import importlib.metadata
import json
import os
import re
import shutil
import signal
import socket
import stat
import subprocess
import sys
import tempfile
import time
import urllib.parse
import urllib.request
from collections.abc import Callable, Iterator, Mapping
from contextlib import ExitStack, contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Any, BinaryIO

from dependency_integrity import DependencyIntegrityError, verified_sha256
from runtime_store import (
    InvalidRuntimeTreeError,
    RecipeNotFoundError,
    RuntimeRecipe,
    RuntimeStore,
    RuntimeStoreError,
    StoreCorruptionError,
)
from scenario_contract import OpaqueStarsProbe, RequiredGuiTextProbe, default_contract
from mod_compatibility import CompatibilityLane


FATAL_LOG_PATTERNS = (
    re.compile(r"(?i)mixin.*(?:apply|inject|target).*(?:fail|error)"),
    re.compile(r"(?i)(?:InvalidInjectionException|InjectionError|MixinApplyError)"),
    re.compile(r"(?i)access widener.*(?:fail|error|invalid)"),
    re.compile(
        r"(?:NoClassDefFoundError|NoSuchMethodError|NoSuchFieldError|"
        r"AbstractMethodError|VerifyError|LinkageError)"
    ),
    re.compile(r"(?i)@ExpectPlatform.*(?:assert|not transformed|missing)"),
    re.compile(r"(?i)(?:ModLoadingException|Failed to load mod|Incompatible mod set)"),
    re.compile(r"(?i)missing or unsupported mandatory dependencies"),
    re.compile(r"(?i)minecraft game provider couldn't locate the game"),
    re.compile(r"(?i)couldn't load (?:function|tag)"),
    re.compile(r"(?i)crash report saved to"),
)
KQUEUE_NATIVE_INIT_FAILURE = (
    "java.lang.NoClassDefFoundError: Could not initialize class "
    "io.netty.channel.kqueue.Native"
)
KQUEUE_UNSUPPORTED_PLATFORM_CAUSE = (
    "java.lang.IllegalStateException: Only supported on OSX/BSD"
)
DEBUG_FILE_APPENDER_FAILURE = "An exception occurred processing Appender DebugFile"
DEBUG_FILE_APPENDER_STACK_WINDOW = 96
COMPATIBILITY_LOG_MARKERS = {
    "neoforge-26.1-break-event-v1": (
        "Quick Skin applied Architectury NeoForge 26.1 BreakEvent compatibility patch"
    ),
}

SCENARIO_CONTRACT = default_contract()

# Transitional collection-shaped views. They are derived from scenario-contract.json at import
# time so existing callers cannot become a second source of E2E truth.
EXPECTED_STEPS: dict[tuple[str, str], list[str]] = {
    (scenario.scenario, role.role): list(role.step_ids)
    for scenario in SCENARIO_CONTRACT.scenarios
    for role in scenario.roles
}
ORCHESTRATION_BY_SCENARIO = {
    scenario.scenario: scenario.orchestration
    for scenario in SCENARIO_CONTRACT.scenarios
}

GUI_TEXT_REFERENCE_SIZE = SCENARIO_CONTRACT.gui_text_reference_size
GuiTextProbe = tuple[str, tuple[int, int, int, int], int, int]
REQUIRED_GUI_TEXT_PROBES: dict[
    tuple[str, str, str], tuple[GuiTextProbe, ...]
] = {
    (scenario.scenario, role.role, step.id): tuple(
        (
            probe.label,
            probe.box,
            probe.minimum_luma_exclusive,
            probe.minimum_pixels,
        )
        for probe in step.capture.probes
        if isinstance(probe, RequiredGuiTextProbe)
    )
    for scenario in SCENARIO_CONTRACT.scenarios
    for role in scenario.roles
    for step in role.steps
    if step.capture is not None
    and any(
        isinstance(probe, RequiredGuiTextProbe)
        for probe in step.capture.probes
    )
}
OPAQUE_STARS_PROBES: dict[tuple[str, str, str], OpaqueStarsProbe] = {
    (scenario.scenario, role.role, step.id): probe
    for scenario in SCENARIO_CONTRACT.scenarios
    for role in scenario.roles
    for step in role.steps
    if step.capture is not None
    for probe in step.capture.probes
    if isinstance(probe, OpaqueStarsProbe)
}
OPAQUE_STARS_SCREENSHOT_REGIONS = {
    key: probe.region for key, probe in OPAQUE_STARS_PROBES.items()
}
_PRIMARY_OPAQUE_STARS_PROBE = next(iter(OPAQUE_STARS_PROBES.values()))
OPAQUE_STARS_BACKGROUND_REGION = _PRIMARY_OPAQUE_STARS_PROBE.region
OPAQUE_STARS_MAXIMUM_MEAN_LUMA = _PRIMARY_OPAQUE_STARS_PROBE.maximum_mean_luma
OPAQUE_STARS_BRIGHT_LUMA = _PRIMARY_OPAQUE_STARS_PROBE.bright_luma
OPAQUE_STARS_MAXIMUM_BRIGHT_FRACTION = (
    _PRIMARY_OPAQUE_STARS_PROBE.maximum_bright_fraction
)

ScreenshotPair = (
    tuple[str, str, float]
    | tuple[str, str, float, tuple[float, float, float, float]]
)
DISTINCT_SCREENSHOT_PAIRS: dict[tuple[str, str], list[ScreenshotPair]] = {
    (scenario.scenario, role.role): [
        (
            comparison.first_step,
            comparison.second_step,
            comparison.minimum_changed_fraction,
            comparison.region,
        )
        if comparison.region is not None
        else (
            comparison.first_step,
            comparison.second_step,
            comparison.minimum_changed_fraction,
        )
        for comparison in role.comparisons
    ]
    for scenario in SCENARIO_CONTRACT.scenarios
    for role in scenario.roles
}


class RuntimeFailure(RuntimeError):
    pass


NEOFORGE_CLIENT_INSTALL_ATTEMPTS = 3
NEOFORGE_CLIENT_INSTALL_BACKOFF_SECONDS = (5, 15)
NEOFORGE_SERVER_INSTALL_ATTEMPTS = 3
NEOFORGE_SERVER_INSTALL_BACKOFF_SECONDS = (5, 15)
LAUNCHER_LIBRARY_VERSION = "8.0"
LAUNCHER_LIBRARY_REVISION = "minecraft-launcher-lib==8.0"
PROFILE_NORMALIZER_REVISION = "normalize-inherited-profile-v1"
DEFAULT_RUNTIME_STORE_MAX_AGE_SECONDS = 14 * 24 * 60 * 60
DEFAULT_RUNTIME_STORE_MAX_BYTES = 20 * 1024 * 1024 * 1024
RUNTIME_STORE_ENV = "QUICKSKIN_E2E_RUNTIME_STORE"
RUNTIME_STORE_MAX_AGE_ENV = "QUICKSKIN_E2E_RUNTIME_STORE_MAX_AGE_SECONDS"
RUNTIME_STORE_MAX_BYTES_ENV = "QUICKSKIN_E2E_RUNTIME_STORE_MAX_BYTES"
SHA256_PATTERN = re.compile(r"[0-9a-f]{64}")
OFFLINE_PLAYER_UUIDS = {
    # Java UUID.nameUUIDFromBytes("OfflinePlayer:<name>".getBytes(UTF_8)).
    # The E2E roles below are deliberately fixed; fail closed if that identity catalog drifts.
    "Alice": "10920508d5d83eed93d292f193afe7d7",
    "Bob": "faa5dca3c3d4354bae1bdde9e5a14b3b",
}

MAX_EVIDENCE_FILES = 512
MAX_EVIDENCE_TOTAL_BYTES = 256 * 1024 * 1024
MAX_EVIDENCE_LOG_BYTES = 16 * 1024 * 1024
MAX_EVIDENCE_REPORT_BYTES = 4 * 1024 * 1024
MAX_EVIDENCE_SCREENSHOT_BYTES = 32 * 1024 * 1024
MAX_EVIDENCE_CRASH_REPORT_BYTES = 16 * 1024 * 1024
PROCESS_GRACEFUL_STOP_SECONDS = 15
PROCESS_FORCE_STOP_SECONDS = 15
PROCESS_GROUP_POLL_SECONDS = 0.1
REPLAYMOD_LOGIN_STALL_SECONDS = 12
REPLAYMOD_CONNECT_LOG_MARKER = "Connecting to "
REPLAYMOD_LOGIN_PROGRESS_MARKERS = (
    "Multiplayer Recording is disabled",
    "[QS-E2E] joined world;",
)


@dataclass(frozen=True)
class RuntimeDependency:
    path: Path
    sha256: str
    filename: str


@dataclass
class ReplayModLoginDiagnostic:
    """One-shot state for a live ReplayMod login-stall thread dump."""

    connecting_seen_at: float | None = None
    dump_requested: bool = False
    login_progressed: bool = False


class PackagedRuntimeSession:
    """One fresh scratch run sharing immutable RuntimeStore recipes across scenarios."""

    def __init__(
        self,
        store: RuntimeStore,
        run_root: Path,
        *,
        gc_max_age: int = DEFAULT_RUNTIME_STORE_MAX_AGE_SECONDS,
        gc_max_bytes: int = DEFAULT_RUNTIME_STORE_MAX_BYTES,
    ) -> None:
        self.store = store
        self.run_root = Path(run_root)
        self.run_root.mkdir(parents=True, exist_ok=True)
        run_stat = self.run_root.lstat()
        if stat.S_ISLNK(run_stat.st_mode) or not stat.S_ISDIR(run_stat.st_mode):
            raise RuntimeFailure(f"runtime session root is not a real directory: {run_root}")
        store_root = self.store.root.resolve()
        resolved_run_root = self.run_root.resolve()
        if (
            store_root == resolved_run_root
            or store_root in resolved_run_root.parents
            or resolved_run_root in store_root.parents
        ):
            raise RuntimeFailure("RuntimeStore and scratch run roots must not overlap")
        self.gc_max_age = _bounded_configuration_integer(
            gc_max_age, "runtime store max age", maximum=365 * 24 * 60 * 60
        )
        self.gc_max_bytes = _bounded_configuration_integer(
            gc_max_bytes, "runtime store max bytes", maximum=1024 * 1024 * 1024 * 1024
        )
        self.installs_root = self.run_root / "client-installs"
        self.scenarios_root = self.run_root / "scenarios"
        self.logs_root = self.run_root / "install-logs"
        for directory in (self.installs_root, self.scenarios_root, self.logs_root):
            try:
                directory.mkdir()
            except FileExistsError as exc:
                raise RuntimeFailure(
                    f"runtime session scratch root is not fresh: {directory}"
                ) from exc
        self._client_installs: dict[str, tuple[Path, str]] = {}

    @classmethod
    def from_environment(
        cls,
        run_root: Path,
        env: Mapping[str, str] | None = None,
    ) -> PackagedRuntimeSession:
        selected_env = os.environ if env is None else env
        cache_root = runtime_store_cache_root(selected_env)
        max_age = _configuration_from_environment(
            selected_env,
            RUNTIME_STORE_MAX_AGE_ENV,
            DEFAULT_RUNTIME_STORE_MAX_AGE_SECONDS,
            maximum=365 * 24 * 60 * 60,
        )
        max_bytes = _configuration_from_environment(
            selected_env,
            RUNTIME_STORE_MAX_BYTES_ENV,
            DEFAULT_RUNTIME_STORE_MAX_BYTES,
            maximum=1024 * 1024 * 1024 * 1024,
        )
        return cls(
            RuntimeStore(cache_root),
            run_root,
            gc_max_age=max_age,
            gc_max_bytes=max_bytes,
        )

    def scenario_profile(self, identity: str) -> Path:
        if safe_id(identity) != identity or not identity:
            raise RuntimeFailure(f"unsafe runtime scenario identity: {identity!r}")
        profile = self.scenarios_root / identity
        try:
            profile.mkdir()
        except FileExistsError as exc:
            raise RuntimeFailure(f"runtime scenario scratch path is not fresh: {profile}") from exc
        return profile

    def memoized_client_install(self, recipe: RuntimeRecipe) -> tuple[Path, str] | None:
        return self._client_installs.get(recipe.digest())

    def remember_client_install(
        self, recipe: RuntimeRecipe, install_dir: Path, version_id: str
    ) -> None:
        digest = recipe.digest()
        if digest in self._client_installs:
            raise RuntimeFailure(f"runtime recipe was materialized more than once: {digest}")
        self._client_installs[digest] = (install_dir, version_id)

    def install_destination(self, recipe: RuntimeRecipe) -> Path:
        return self.installs_root / recipe.digest()

    def install_log(self, recipe: RuntimeRecipe) -> Path:
        return self.logs_root / f"{recipe.digest()}.log"

    def metrics(self) -> dict[str, int]:
        metrics = self.store.metrics
        return {
            "hits": metrics.hits,
            "misses": metrics.misses,
            "pruned_entries": metrics.pruned,
            "pruned_bytes": metrics.pruned_bytes,
            "total_bytes": self.store.total_blob_bytes(),
        }

    def gc(self) -> dict[str, int]:
        self.store.gc(max_age=self.gc_max_age, max_bytes=self.gc_max_bytes)
        return self.metrics()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _bounded_configuration_integer(value: object, label: str, *, maximum: int) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or not 0 <= value <= maximum:
        raise RuntimeFailure(f"{label} must be an integer between 0 and {maximum}")
    return value


def _configuration_from_environment(
    env: Mapping[str, str], name: str, default: int, *, maximum: int
) -> int:
    raw = env.get(name)
    if raw is None:
        return default
    if not isinstance(raw, str) or not raw or raw != raw.strip() or not raw.isdecimal():
        raise RuntimeFailure(f"{name} must be a non-negative decimal integer")
    return _bounded_configuration_integer(int(raw), name, maximum=maximum)


def runtime_store_cache_root(env: Mapping[str, str] | None = None) -> Path:
    """Resolve the persistent cache root independently from any E2E output tree."""

    selected_env = os.environ if env is None else env
    configured = selected_env.get(RUNTIME_STORE_ENV)
    if configured is not None:
        if not isinstance(configured, str) or not configured or configured != configured.strip():
            raise RuntimeFailure(f"{RUNTIME_STORE_ENV} must be a non-empty trimmed path")
        return Path(configured).expanduser().resolve()
    if sys.platform == "darwin":
        return (Path.home() / "Library" / "Caches" / "QuickSkin" / "e2e").resolve()
    if os.name == "nt":
        local_app_data = selected_env.get("LOCALAPPDATA")
        base = Path(local_app_data) if local_app_data else Path.home() / "AppData" / "Local"
        return (base / "QuickSkin" / "e2e").expanduser().resolve()
    xdg_cache = selected_env.get("XDG_CACHE_HOME")
    base = Path(xdg_cache).expanduser() if xdg_cache else Path.home() / ".cache"
    return (base / "quickskin" / "e2e").resolve()


def allocate_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def safe_id(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value)


def java_executable(major: int) -> str:
    candidates = [
        os.environ.get(f"QUICKSKIN_JAVA_{major}"),
        os.environ.get(f"JAVA_HOME_{major}_X64"),
        os.environ.get(f"JAVA_HOME_{major}_x64"),
    ]
    for candidate in candidates:
        if not candidate:
            continue
        path = Path(candidate)
        if path.is_dir():
            path = path / "bin" / ("java.exe" if os.name == "nt" else "java")
        if path.is_file():
            return str(path)
    if os.environ.get("JAVA_HOME"):
        path = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if path.is_file() and detected_java_major(str(path)) == major:
            return str(path)
    found = shutil.which("java")
    if found and detected_java_major(found) == major:
        return found
    raise RuntimeFailure(
        f"Java {major} not found; configure QUICKSKIN_JAVA_{major} or JAVA_HOME_{major}_X64"
    )


def detected_java_major(java: str) -> int | None:
    try:
        output = subprocess.check_output(
            [java, "-version"], stderr=subprocess.STDOUT, text=True, timeout=15
        )
    except (OSError, subprocess.SubprocessError):
        return None
    match = re.search(r'version "(?:1\.)?(\d+)', output)
    return int(match.group(1)) if match else None


def launcher_library_version() -> str:
    try:
        version = importlib.metadata.version("minecraft-launcher-lib")
    except importlib.metadata.PackageNotFoundError as exc:
        raise RuntimeFailure(
            "minecraft-launcher-lib is not installed; run "
            "`python -m pip install -r e2e/requirements.txt`"
        ) from exc
    if version != LAUNCHER_LIBRARY_VERSION:
        raise RuntimeFailure(
            "minecraft-launcher-lib must be exactly "
            f"{LAUNCHER_LIBRARY_VERSION}, found {version}"
        )
    return version


def client_runtime_recipe(
    matrix: dict[str, Any], row: dict[str, Any]
) -> RuntimeRecipe:
    installer = matrix.get("installers", {}).get(row.get("installer"))
    if not isinstance(installer, dict):
        raise RuntimeFailure(f"runtime installer is missing for {row.get('installer')!r}")
    installer_sha256 = installer.get("sha256")
    if not isinstance(installer_sha256, str) or SHA256_PATTERN.fullmatch(installer_sha256) is None:
        raise RuntimeFailure("runtime installer must have one exact lowercase SHA-256")
    launcher_library_version()
    return RuntimeRecipe.for_host(
        java_major=int(row["java"]),
        minecraft_version=row["runtime_version"],
        loader=row["loader"],
        loader_version=row["loader_version"],
        installer_sha256=installer_sha256,
        launcher_library_revision=LAUNCHER_LIBRARY_REVISION,
        normalizer_revision=PROFILE_NORMALIZER_REVISION,
    )


def download(url: str, destination: Path, expected_sha256: str) -> Path:
    if not url.startswith("https://"):
        raise RuntimeFailure(f"refusing non-HTTPS runtime download: {url}")
    if SHA256_PATTERN.fullmatch(expected_sha256) is None:
        raise RuntimeFailure("runtime download requires one exact lowercase SHA-256")
    if destination.is_symlink():
        raise RuntimeFailure(f"refusing symlinked runtime download destination: {destination}")
    if destination.is_file() and sha256(destination) == expected_sha256:
        return destination
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_suffix(destination.suffix + ".part")
    request = urllib.request.Request(
        url,
        headers={"User-Agent": "The-Plum-Team/Quick-Skin-Mod packaged-e2e"},
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as out:
            shutil.copyfileobj(response, out)
    except Exception as exc:
        temporary.unlink(missing_ok=True)
        raise RuntimeFailure(f"failed to download {url}: {exc}") from exc
    if sha256(temporary) != expected_sha256:
        actual = sha256(temporary)
        temporary.unlink(missing_ok=True)
        raise RuntimeFailure(
            f"download SHA-256 mismatch for {url}: expected {expected_sha256}, got {actual}"
        )
    temporary.replace(destination)
    return destination


def fetch_verified_blob(
    store: RuntimeStore,
    *,
    url: str,
    filename: str,
    expected_sha256: str,
) -> Path:
    """Download to throwaway storage, verify, then admit to the immutable store."""

    if Path(filename).name != filename or "/" in filename or "\\" in filename:
        raise RuntimeFailure(f"unsafe runtime dependency filename: {filename!r}")
    with tempfile.TemporaryDirectory(prefix="verified-download-", dir=store.tmp_dir) as temporary:
        downloaded = download(url, Path(temporary) / filename, expected_sha256)
        try:
            return store.admit_blob(downloaded, expected_sha256)
        except (InvalidRuntimeTreeError, RuntimeStoreError) as exc:
            raise RuntimeFailure(f"cannot admit verified runtime blob {filename}: {exc}") from exc


@contextmanager
def leased_verified_blob(
    store: RuntimeStore,
    *,
    url: str,
    filename: str,
    expected_sha256: str,
) -> Iterator[Path]:
    """Reuse an exact blob or rebuild corrupt/missing content from its trusted source."""

    lease_stack = ExitStack()
    try:
        try:
            path = lease_stack.enter_context(store.lease_blob(expected_sha256))
        except (RecipeNotFoundError, StoreCorruptionError):
            lease_stack.close()
            fetch_verified_blob(
                store,
                url=url,
                filename=filename,
                expected_sha256=expected_sha256,
            )
            lease_stack = ExitStack()
            path = lease_stack.enter_context(store.lease_blob(expected_sha256))
        yield path
    finally:
        lease_stack.close()


_CONTENT_ADDRESSED_NAME = re.compile(r"[0-9a-f]{64}")


def copy_verified(
    source: Path,
    destination_dir: Path,
    expected_sha256: str,
    *,
    name: str | None = None,
) -> Path:
    """Install one verified file, optionally renaming a content-addressed blob.

    Store blobs are named by digest, but loaders only discover ``*.jar``, so a
    leased dependency must be installed under its real Maven artifact name.
    """

    if name is not None and (name != Path(name).name or name in {"", ".", ".."}):
        raise RuntimeFailure(f"unsafe installed package name: {name!r}")
    if name is None and _CONTENT_ADDRESSED_NAME.fullmatch(source.name):
        raise RuntimeFailure(
            "refusing to install content-addressed blob under its digest name; "
            f"pass the real artifact name for {source}"
        )
    if not source.is_file():
        raise RuntimeFailure(f"package source does not exist: {source}")
    actual = sha256(source)
    if actual != expected_sha256:
        raise RuntimeFailure(
            f"package source hash mismatch for {source.name}: expected {expected_sha256}, got {actual}"
        )
    destination_dir.mkdir(parents=True, exist_ok=True)
    destination = destination_dir / (name or source.name)
    shutil.copy2(source, destination)
    if sha256(destination) != expected_sha256:
        raise RuntimeFailure(f"installed package hash mismatch for {destination}")
    return destination


def maven_dependency_url(loader: str, version: str, fabric_api: bool = False) -> tuple[str, str]:
    if fabric_api:
        filename = f"fabric-api-{version}.jar"
        encoded = urllib.parse.quote(version, safe="+.-_")
        return (
            f"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{encoded}/{filename}",
            filename,
        )
    module = {
        "fabric": "architectury-fabric",
        "forge": "architectury-forge",
        "neoforge": "architectury-neoforge",
    }[loader]
    filename = f"{module}-{version}.jar"
    return (
        f"https://maven.architectury.dev/dev/architectury/{module}/{version}/{filename}",
        filename,
    )


def verified_dependency_sha256(
    verification_metadata: Path,
    *,
    group: str,
    name: str,
    version: str,
    artifact: str,
) -> str:
    try:
        return verified_sha256(
            verification_metadata,
            group=group,
            name=name,
            version=version,
            artifact=artifact,
        )
    except DependencyIntegrityError as exc:
        raise RuntimeFailure(f"runtime dependency has no exact Gradle SHA-256: {exc}") from exc


@contextmanager
def runtime_dependencies(
    row: dict[str, Any],
    store: RuntimeStore,
    verification_metadata: Path,
) -> Iterator[list[RuntimeDependency]]:
    """Lease runtime dependencies whose hashes come only from Gradle's trust authority."""

    specifications: list[tuple[str, str, str, str, str]] = []
    if row["loader"] == "fabric":
        version = row["fabric_api"]
        url, filename = maven_dependency_url("fabric", version, fabric_api=True)
        specifications.append(
            ("net.fabricmc.fabric-api", "fabric-api", version, url, filename)
        )

    architectury = row["architectury"]
    if architectury["kind"] != "maven":
        raise RuntimeFailure(f"unknown Architectury dependency kind {architectury['kind']!r}")
    module = {
        "fabric": "architectury-fabric",
        "forge": "architectury-forge",
        "neoforge": "architectury-neoforge",
    }[row["loader"]]
    version = architectury["version"]
    url, filename = maven_dependency_url(row["loader"], version)
    specifications.append(("dev.architectury", module, version, url, filename))

    with ExitStack() as leases:
        dependencies: list[RuntimeDependency] = []
        for group, name, version, url, filename in specifications:
            expected_sha256 = verified_dependency_sha256(
                verification_metadata,
                group=group,
                name=name,
                version=version,
                artifact=filename,
            )
            path = leases.enter_context(
                leased_verified_blob(
                    store,
                    url=url,
                    filename=filename,
                    expected_sha256=expected_sha256,
                )
            )
            dependencies.append(RuntimeDependency(path, expected_sha256, filename))
        yield dependencies


def run_checked(
    command: list[str],
    cwd: Path,
    log_path: Path,
    env: dict[str, str],
    timeout: int = 1800,
    *,
    append: bool = False,
) -> None:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("ab" if append else "wb") as log:
        process = subprocess.run(
            command,
            cwd=cwd,
            env=env,
            stdout=log,
            stderr=subprocess.STDOUT,
            timeout=timeout,
            check=False,
        )
    if process.returncode:
        tail = log_path.read_text(encoding="utf-8", errors="replace").splitlines()[-40:]
        raise RuntimeFailure(
            f"command failed ({process.returncode}): {' '.join(command)}\n" + "\n".join(tail)
        )


@contextmanager
def leased_installer(
    matrix: dict[str, Any], row: dict[str, Any], store: RuntimeStore
) -> Iterator[Path]:
    installer = matrix.get("installers", {}).get(row.get("installer"))
    if not isinstance(installer, dict):
        raise RuntimeFailure(f"runtime installer is missing for {row.get('installer')!r}")
    url = installer.get("url")
    expected_sha256 = installer.get("sha256")
    if not isinstance(url, str) or not url.startswith("https://"):
        raise RuntimeFailure("runtime installer must use one exact HTTPS URL")
    if not isinstance(expected_sha256, str) or SHA256_PATTERN.fullmatch(expected_sha256) is None:
        raise RuntimeFailure("runtime installer must have one exact lowercase SHA-256")
    filename = Path(urllib.parse.urlparse(url).path).name
    with leased_verified_blob(
        store,
        url=url,
        filename=filename,
        expected_sha256=expected_sha256,
    ) as path:
        yield path


def ensure_launcher_files(directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    profiles = directory / "launcher_profiles.json"
    if not profiles.exists():
        profiles.write_text('{"profiles":{}}\n', encoding="utf-8")


def installed_version_id(row: dict[str, Any]) -> str:
    if row["loader"] == "fabric":
        return f"fabric-loader-{row['loader_version']}-{row['runtime_version']}"
    if row["loader"] == "forge":
        forge_loader = row["loader_version"].removeprefix(f"{row['runtime_version']}-")
        return f"{row['runtime_version']}-forge-{forge_loader}"
    if row["loader"] == "neoforge":
        return f"neoforge-{row['loader_version']}"
    raise RuntimeFailure(f"unsupported loader {row['loader']!r}")


def remove_install_path(path: Path) -> None:
    if path.is_dir() and not path.is_symlink():
        shutil.rmtree(path)
    else:
        path.unlink(missing_ok=True)


def normalize_inherited_profile(version_json: Path, loader: str) -> None:
    """Make inherited loader profiles explicit for minecraft-launcher-lib 8.0.

    Loader installers normally omit ``jar`` and rely on the official launcher
    to select the inherited vanilla jar.  minecraft-launcher-lib instead falls
    back to the loader profile id. Fabric needs an explicit inherited jar.
    Forge and NeoForge intentionally keep the profile-id tail nonexistent: their
    ModLauncher bootstrap owns the transformed Minecraft module, and putting
    the vanilla jar on its classpath creates a duplicate-module failure.
    """
    try:
        data = json.loads(version_json.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeFailure(f"invalid installed loader profile {version_json}: {exc}") from exc
    inherited = data.get("inheritsFrom")
    if inherited:
        if loader != "fabric":
            changed = data.pop("jar", None) is not None
            stale_loader_jar = version_json.with_suffix(".jar")
            if stale_loader_jar.is_file():
                stale_loader_jar.unlink()
            if changed:
                version_json.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")
            return
        inherited_jar = version_json.parent.parent / inherited / f"{inherited}.jar"
        if not inherited_jar.is_file():
            raise RuntimeFailure(
                f"installed loader profile {version_json} inherits missing base jar {inherited_jar}"
            )
        selected_jar = inherited
        if data.get("jar") != selected_jar:
            data["jar"] = selected_jar
            version_json.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def prepare_client_install(
    matrix: dict[str, Any],
    row: dict[str, Any],
    session: PackagedRuntimeSession,
    java: str,
) -> tuple[Path, str]:
    recipe = client_runtime_recipe(matrix, row)
    memoized = session.memoized_client_install(recipe)
    if memoized is not None:
        return memoized

    import minecraft_launcher_lib.install  # type: ignore[import-not-found]

    key = safe_id(f"{row['loader']}-{row['runtime_version']}-{row['loader_version']}")
    version_id = installed_version_id(row)
    install_log = session.install_log(recipe)

    with leased_installer(matrix, row, session.store) as installer:

        def build(staging: Path) -> None:
            attempts = (
                NEOFORGE_CLIENT_INSTALL_ATTEMPTS if row["loader"] == "neoforge" else 1
            )
            last_error: Exception | None = None
            install_log.parent.mkdir(parents=True, exist_ok=True)
            install_log.write_text("", encoding="utf-8")
            for attempt_number in range(1, attempts + 1):
                attempt = staging / f".install-attempt-{attempt_number}"
                attempt.mkdir()
                staged_version_json = (
                    attempt / "versions" / version_id / f"{version_id}.json"
                )
                try:
                    ensure_launcher_files(attempt)
                    with install_log.open("a", encoding="utf-8") as log:
                        log.write(
                            f"Client install attempt {attempt_number}/{attempts}: "
                            f"vanilla Minecraft {row['runtime_version']}\n"
                        )
                    minecraft_launcher_lib.install.install_minecraft_version(
                        row["runtime_version"], str(attempt)
                    )
                    if row["loader"] == "fabric":
                        arguments = [
                            java,
                            "-jar",
                            str(installer),
                            "client",
                            "-dir",
                            str(attempt),
                            "-mcversion",
                            row["runtime_version"],
                            "-loader",
                            row["loader_version"],
                            "-noprofile",
                            "-snapshot",
                        ]
                    elif row["loader"] == "forge":
                        arguments = [
                            java,
                            "-jar",
                            str(installer),
                            "--installClient",
                            str(attempt),
                        ]
                    elif row["loader"] == "neoforge":
                        # Bypass minecraft-launcher-lib's pre-26.x NeoForge normalizer while
                        # retaining its standard command builder for the resulting profile.
                        arguments = [
                            java,
                            "-jar",
                            str(installer),
                            "--install-client",
                            str(attempt),
                        ]
                    else:
                        raise RuntimeFailure(f"unsupported loader {row['loader']!r}")
                    run_checked(
                        arguments,
                        attempt,
                        install_log,
                        process_env(java),
                        timeout=1800,
                        append=True,
                    )
                    if not staged_version_json.is_file():
                        raise RuntimeFailure(
                            f"loader installer did not create {staged_version_json}"
                        )
                    normalize_inherited_profile(staged_version_json, row["loader"])
                    for child in list(attempt.iterdir()):
                        child.replace(staging / child.name)
                    attempt.rmdir()
                    return
                except Exception as exc:
                    last_error = exc
                    with install_log.open("a", encoding="utf-8") as log:
                        log.write(
                            f"Client install attempt {attempt_number}/{attempts} "
                            f"failed: {exc}\n"
                        )
                    # A failure can happen while the completed attempt is being moved into
                    # RuntimeStore's private staging root. Discard every partial child before
                    # retrying so a later attempt can never inherit or overwrite mixed content.
                    for partial in list(staging.iterdir()):
                        remove_install_path(partial)
                    if attempt_number < attempts:
                        time.sleep(
                            NEOFORGE_CLIENT_INSTALL_BACKOFF_SECONDS[attempt_number - 1]
                        )
            if last_error is None:
                raise RuntimeFailure(f"client installation made no attempts for {key}")
            raise RuntimeFailure(
                f"client installation failed after {attempts} attempt(s) for {key}: "
                f"{last_error}"
            ) from last_error

        # Keep the recipe/tree/blob lease continuously from lookup/publication through
        # materialization. A concurrent RuntimeStore GC must never observe a gap here.
        destination = session.install_destination(recipe)
        session.store.materialize_get_or_create(recipe, build, destination)

    version_json = destination / "versions" / version_id / f"{version_id}.json"
    try:
        profile = json.loads(version_json.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeFailure(f"materialized loader profile is invalid: {version_json}: {exc}") from exc
    if not isinstance(profile, dict):
        raise RuntimeFailure(f"materialized loader profile is not an object: {version_json}")
    session.remember_client_install(recipe, destination, version_id)
    return destination, version_id


def process_env(java: str) -> dict[str, str]:
    env = os.environ.copy()
    java_home = str(Path(java).resolve().parent.parent)
    env["JAVA_HOME"] = java_home
    env["PATH"] = str(Path(java).resolve().parent) + os.pathsep + env.get("PATH", "")
    return env


def prepare_server(
    matrix: dict[str, Any],
    row: dict[str, Any],
    server: Path,
    store: RuntimeStore,
    java: str,
    log: Path,
) -> list[str]:
    env = process_env(java)
    with leased_installer(matrix, row, store) as installer:
        if row["loader"] == "fabric":
            arguments = [
                java,
                "-jar",
                str(installer),
                "server",
                "-dir",
                str(server),
                "-mcversion",
                row["runtime_version"],
                "-loader",
                row["loader_version"],
                "-downloadMinecraft",
            ]
            run_checked(arguments, server, log, env)
            launcher = server / "fabric-server-launch.jar"
            if not launcher.is_file():
                raise RuntimeFailure(f"Fabric server launcher was not created at {launcher}")
            return [java, "-Xms512M", "-Xmx1024M", "-jar", str(launcher), "nogui"]

        if row["loader"] not in {"forge", "neoforge"}:
            raise RuntimeFailure(f"unsupported loader {row['loader']!r}")
        if row["loader"] == "forge":
            run_checked(
                [java, "-jar", str(installer), "--installServer", str(server)],
                server,
                log,
                env,
            )
        else:
            # NeoForge installers download Maven dependencies at install time. A transient
            # CDN failure must not leave a half-populated server tree for the retry (or for a
            # later scenario) to consume, so every attempt gets an isolated staging directory.
            last_error: Exception | None = None
            for attempt_number in range(1, NEOFORGE_SERVER_INSTALL_ATTEMPTS + 1):
                attempt = Path(
                    tempfile.mkdtemp(
                        prefix=f".neoforge-server-attempt-{attempt_number}-",
                        dir=server.parent,
                    )
                )
                try:
                    run_checked(
                        [java, "-jar", str(installer), "--install-server", str(attempt)],
                        attempt,
                        log,
                        env,
                        append=attempt_number > 1,
                    )
                    expected_script = attempt / ("run.bat" if os.name == "nt" else "run.sh")
                    if not expected_script.is_file():
                        raise RuntimeFailure(
                            f"NeoForge server installer did not create {expected_script}"
                        )
                    if any(server.iterdir()):
                        raise RuntimeFailure(
                            f"refusing to replace non-empty server directory {server}"
                        )
                    server.rmdir()
                    os.replace(attempt, server)
                    last_error = None
                    break
                except Exception as exc:
                    last_error = exc
                    if attempt_number < NEOFORGE_SERVER_INSTALL_ATTEMPTS:
                        time.sleep(
                            NEOFORGE_SERVER_INSTALL_BACKOFF_SECONDS[attempt_number - 1]
                        )
                finally:
                    shutil.rmtree(attempt, ignore_errors=True)
            if last_error is not None:
                raise RuntimeFailure(
                    "NeoForge server installation failed after "
                    f"{NEOFORGE_SERVER_INSTALL_ATTEMPTS} isolated attempts: {last_error}"
                ) from last_error
    (server / "user_jvm_args.txt").write_text("-Xms512M\n-Xmx1024M\n", encoding="utf-8")
    if os.name == "nt":
        script = server / "run.bat"
        if not script.is_file():
            raise RuntimeFailure(f"server installer did not create {script}")
        return ["cmd", "/c", str(script), "nogui"]
    script = server / "run.sh"
    if not script.is_file():
        raise RuntimeFailure(f"server installer did not create {script}")
    return ["bash", str(script), "nogui"]


def write_server_files(server: Path, port: int, template_root: Path) -> None:
    properties = (template_root / "server.properties").read_text(encoding="utf-8")
    properties = re.sub(r"(?m)^server-port=.*$", f"server-port={port}", properties)
    (server / "server.properties").write_text(properties, encoding="utf-8")
    shutil.copy2(template_root / "eula.txt", server / "eula.txt")
    datapack = server / "world" / "datapacks" / "qs_e2e_time"
    datapack.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(template_root / "datapack", datapack, dirs_exist_ok=True)


def write_e2e_client_config(game_dir: Path) -> Path:
    """Seed a clean Quick Skin profile before client initialization.

    Packaged runs exercise skin import explicitly in their scenario steps.  Letting the normal
    Mojang own-skin importer race those steps would replace the UUID-selected vanilla baseline
    with account data whose arrival time depends on the network.  Each E2E game directory is
    disposable, so disable that importer and start with no persisted selection before Minecraft
    loads the mod.
    """

    config_path = game_dir / "config" / "quickskin-client.json"
    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(
        json.dumps(
            {
                "enablePlayerOwnSkinSystem": False,
                "activeSkinHash": "",
                "activeCpmModelHash": "",
                "activeCapeHash": "",
                "playerOwnSkinHash": "",
            },
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    return config_path


def write_compatibility_client_config(
    game_dir: Path, compatibility_mod: str
) -> Path | None:
    """Seed only the third-party settings needed for a deterministic compatibility probe.

    ReplayMod records every multiplayer connection by default. Recording is unrelated to the
    Quick Skin rendering bridge exercised by this lane, and a replay writer/recovery failure in a
    disposable profile would be an upstream false failure. Use ReplayMod's own supported setting
    to leave the integration loaded while preventing that unrelated recorder from owning the test
    connection.
    """

    if compatibility_mod != "replaymod":
        return None

    config_path = game_dir / "config" / "replaymod.json"
    if config_path.exists() or config_path.is_symlink():
        raise RuntimeFailure(
            f"compatibility client config must start absent: {config_path}"
        )
    config_path.parent.mkdir(parents=True, exist_ok=True)
    config_path.write_text(
        json.dumps(
            {"recording": {"recordServer": False}},
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )
    return config_path


def offline_player_uuid(username: str) -> str:
    """Return Java's ``UUID.nameUUIDFromBytes`` identity for an offline player."""

    try:
        return OFFLINE_PLAYER_UUIDS[username]
    except KeyError as exc:
        raise RuntimeFailure(f"no locked offline UUID for E2E username {username!r}") from exc


def client_command(
    install_dir: Path,
    version_id: str,
    game_dir: Path,
    row: dict[str, Any],
    scenario: str,
    role: str,
    username: str,
    port: int,
    java: str,
    compatibility_mod: str | None = None,
) -> list[str]:
    import minecraft_launcher_lib.command  # type: ignore[import-not-found]
    import minecraft_launcher_lib.utils  # type: ignore[import-not-found]

    options = minecraft_launcher_lib.utils.generate_test_options()
    options.update(
        {
            "username": username,
            # The offline server derives this same UUID from the player name. Keeping the
            # launch profile and server profile identical makes UUID-selected vanilla skins
            # deterministic and prevents the client from briefly rendering a different fallback.
            "uuid": offline_player_uuid(username),
            "token": "quickskin-e2e-offline",
            "executablePath": java,
            "defaultExecutablePath": java,
            "gameDirectory": str(game_dir),
            "customResolution": True,
            # Must fit inside the virtual display the CI workflows start (see the xvfb-run
            # --server-args in on-demand-e2e.yml and release.yml); a window larger than the
            # screen is silently clamped and the evidence stops matching what was asked for.
            # Pixel comparisons are unaffected by this number: the regions are fractional, so
            # the same transition measured 0.0723 at 2560x1440 locally and 0.0725 at 1280x720
            # in CI. It only governs how legible the captured evidence is.
            "resolutionWidth": "1920",
            "resolutionHeight": "1080",
            "jvmArguments": [
                "-Xms512M",
                "-Xmx1024M",
                "-Dquickskin.e2e.enabled=true",
                f"-Dquickskin.e2e.role={role}",
                f"-Dquickskin.e2e.scenario={scenario}",
                f"-Dquickskin.e2e.version={row['runtime_version']}",
                "-Dfml.earlyprogresswindow=false",
            ],
        }
    )
    if compatibility_mod is None:
        # Exercise Quick Skin's own injector expectations in the clean runtime. Enabling this
        # global Mixin debug switch in compatibility lanes also turns optional injectors owned
        # by third-party mods into fatal assertions, which is not production-equivalent.
        options["jvmArguments"].append("-Dmixin.debug.countInjections=true")
    else:
        options["jvmArguments"].append(
            f"-Dquickskin.e2e.compatibility={compatibility_mod}"
        )
    if compatibility_mod == "replaymod" and row["runtime_version"] == "1.20.1":
        # ReplayMod makes Minecraft 1.20.1's startup Quick Play connection intermittently stop
        # consuming Fabric login queries. Exercise the production-equivalent user path instead:
        # let startup settle at the title screen, then have the harness initiate a normal connect.
        options["jvmArguments"].append(
            f"-Dquickskin.e2e.delayedConnect=127.0.0.1:{port}"
        )
    else:
        options["quickPlayMultiplayer"] = f"127.0.0.1:{port}"
    return minecraft_launcher_lib.command.get_minecraft_command(
        version_id, str(install_dir), options
    )


def start_process(command: list[str], cwd: Path, log_path: Path, env: dict[str, str]) -> tuple[subprocess.Popen[bytes], BinaryIO]:
    log_path.parent.mkdir(parents=True, exist_ok=True)
    handle = log_path.open("wb")
    kwargs: dict[str, Any] = {"start_new_session": True} if os.name != "nt" else {
        "creationflags": subprocess.CREATE_NEW_PROCESS_GROUP
    }
    try:
        process = subprocess.Popen(
            command,
            cwd=cwd,
            env=env,
            stdout=handle,
            stderr=subprocess.STDOUT,
            **kwargs,
        )
    except Exception:
        handle.close()
        raise
    return process, handle


def _posix_process_group_exists(process_group_id: int) -> bool:
    """Return whether any process still belongs to an isolated launcher group."""

    try:
        os.killpg(process_group_id, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        # A group that exists but cannot be signalled is still not safe to snapshot.
        return True
    return True


def _wait_for_posix_process_group_exit(
    process: subprocess.Popen[bytes],
    process_group_id: int,
    timeout: float,
) -> bool:
    """Reap the launcher and wait until every descendant in its group has exited."""

    deadline = time.monotonic() + timeout
    while True:
        # poll() performs the non-blocking waitpid needed to reap an exited launcher. The
        # group probe remains authoritative because Forge starts through run.sh: that shell
        # can exit before its Java child has finished shutdown logging.
        process.poll()
        if not _posix_process_group_exists(process_group_id):
            return True
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return False
        time.sleep(min(PROCESS_GROUP_POLL_SECONDS, remaining))


def stop_process(process: subprocess.Popen[bytes] | None) -> None:
    if process is None:
        return
    if os.name == "nt":
        if process.poll() is not None:
            return
        try:
            subprocess.run(
                ["taskkill", "/PID", str(process.pid), "/T", "/F"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                timeout=20,
            )
            # taskkill returning only means the termination request completed. Reap the
            # launcher before closing its inherited log descriptor and snapshotting evidence.
            process.wait(timeout=PROCESS_FORCE_STOP_SECONDS)
        except (OSError, subprocess.SubprocessError):
            if process.poll() is None:
                process.kill()
            process.wait(timeout=PROCESS_FORCE_STOP_SECONDS)
        return

    process_group_id = process.pid
    if not _posix_process_group_exists(process_group_id):
        process.poll()
        return
    try:
        os.killpg(process_group_id, signal.SIGTERM)
    except ProcessLookupError:
        process.poll()
        return
    except OSError as exc:
        raise RuntimeFailure(
            f"could not stop isolated process group {process_group_id}"
        ) from exc
    if _wait_for_posix_process_group_exit(
        process,
        process_group_id,
        PROCESS_GRACEFUL_STOP_SECONDS,
    ):
        return

    try:
        os.killpg(process_group_id, signal.SIGKILL)
    except ProcessLookupError:
        process.poll()
        return
    except OSError as exc:
        raise RuntimeFailure(
            f"could not force-stop isolated process group {process_group_id}"
        ) from exc
    if not _wait_for_posix_process_group_exit(
        process,
        process_group_id,
        PROCESS_FORCE_STOP_SECONDS,
    ):
        raise RuntimeFailure(
            f"isolated process group {process_group_id} did not quiesce"
        )


def wait_for_log(process: subprocess.Popen[bytes], log: Path, text: str, timeout: int) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        content = log.read_text(encoding="utf-8", errors="replace") if log.exists() else ""
        if text in content:
            return
        if process.poll() is not None:
            raise RuntimeFailure(f"process exited before {text!r}; see {log}")
        time.sleep(2)
    raise RuntimeFailure(f"timed out waiting for {text!r}; see {log}")


def wait_for_marker(
    process: subprocess.Popen[bytes],
    game_dir: Path,
    role: str,
    timeout: int = 600,
    on_poll: Callable[[], None] | None = None,
) -> str:
    marker = game_dir / "e2e-report" / "done.marker"
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        value = marker_value(game_dir, role)
        if value is not None:
            return value
        if process.poll() is not None:
            raise RuntimeFailure(f"{role} exited before writing {marker}")
        if on_poll is not None:
            on_poll()
        time.sleep(2)
    raise RuntimeFailure(f"timed out waiting for {role} marker {marker}")


def maybe_capture_replaymod_login_stall(
    process: subprocess.Popen[bytes],
    log: Path,
    state: ReplayModLoginDiagnostic,
    *,
    server_process: subprocess.Popen[bytes] | None = None,
    server_log: Path | None = None,
    now: float | None = None,
) -> None:
    """Request one live JVM thread dump when ReplayMod stalls inside multiplayer login.

    The vanilla timeout tears the login handler down after 30 seconds, so a post-failure dump only
    shows idle processes. ReplayMod 1.20.1 is a Fabric-only lane whose client and server launcher
    processes are the JVMs themselves. SIGQUIT therefore records both sides' Java stacks in their
    already bounded logs without terminating or otherwise changing the attempted login.
    """

    if state.dump_requested or state.login_progressed or process.poll() is not None:
        return
    content = log.read_text(encoding="utf-8", errors="replace") if log.exists() else ""
    if any(marker in content for marker in REPLAYMOD_LOGIN_PROGRESS_MARKERS):
        state.login_progressed = True
        return
    if REPLAYMOD_CONNECT_LOG_MARKER not in content:
        return

    observed_at = time.monotonic() if now is None else now
    if state.connecting_seen_at is None:
        state.connecting_seen_at = observed_at
        return
    if observed_at - state.connecting_seen_at < REPLAYMOD_LOGIN_STALL_SECONDS:
        return

    # Mark first so an unsupported signal or a process exiting during the probe cannot create a
    # signal storm on every two-second poll.
    state.dump_requested = True
    if os.name == "nt":
        print(
            "ReplayMod login is stalled, but live JVM thread dumps are unavailable on Windows.",
            flush=True,
        )
        return
    targets = [("client", process, log)]
    if server_process is not None and server_log is not None:
        targets.append(("server", server_process, server_log))
    for target, target_process, target_log in targets:
        if target_process.poll() is not None:
            print(
                f"Could not request ReplayMod {target} thread dump: process exited.",
                flush=True,
            )
            continue
        try:
            target_process.send_signal(signal.SIGQUIT)
        except OSError as exc:
            print(
                f"Could not request ReplayMod {target} thread dump: {exc}",
                flush=True,
            )
            continue
        print(
            f"ReplayMod login made no progress for {REPLAYMOD_LOGIN_STALL_SECONDS}s; "
            f"requested a live {target} JVM thread dump in {target_log}.",
            flush=True,
        )


def marker_value(game_dir: Path, role: str) -> str | None:
    """Return a completed harness marker, or ``None`` while it has not been written."""

    marker = game_dir / "e2e-report" / "done.marker"
    if not marker.is_file():
        return None
    value = marker.read_text(encoding="utf-8").strip()
    if value not in {"pass", "fail"}:
        raise RuntimeFailure(f"invalid {role} done.marker value {value!r}")
    return value


def is_transient_connect_timeout_report(
    game_dir: Path,
    row: Mapping[str, Any],
    scenario: str,
    role: str,
) -> bool:
    """Recognize only the harness's exact pre-world vanilla connection-timeout category."""

    report_path = game_dir / "e2e-report" / "report.json"
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return False
    if not isinstance(report, dict):
        return False
    if (
        report.get("version") != row.get("runtime_version")
        or report.get("role") != role
        or report.get("scenario") != scenario
        or report.get("contract_sha256") != SCENARIO_CONTRACT.sha256
        or report.get("status") != "fail"
    ):
        return False
    steps = report.get("steps")
    if not isinstance(steps, list) or len(steps) != 1 or not isinstance(steps[0], dict):
        return False
    step = steps[0]
    message = step.get("message")
    return (
        step.get("name") == "join_world"
        and step.get("status") == "fail"
        and isinstance(message, str)
        and message.startswith("category=connection_timeout;")
    )


def archive_transient_connect_attempt(
    profile: Path,
    game_dir: Path,
    client_log: Path,
    role: str,
) -> None:
    """Move the first failed login's bounded evidence aside before one fresh client retry."""

    if role not in {"client_a", "client_b"} or game_dir != profile / role:
        raise RuntimeFailure("unsafe transient-connect diagnostic identity")
    if client_log != profile / "logs" / f"{role}.log":
        raise RuntimeFailure("unsafe transient-connect diagnostic log path")
    destination = profile / "diagnostics" / "transient-connect-attempt-1" / role
    if destination.exists() or destination.is_symlink():
        raise RuntimeFailure(f"transient-connect diagnostics already exist: {destination}")
    (destination / "logs").mkdir(parents=True)

    moves = (
        (client_log, destination / "logs" / "client.log", True),
        (game_dir / "e2e-report", destination / "e2e-report", True),
        (game_dir / "screenshots", destination / "screenshots", False),
    )
    for source, target, required in moves:
        if source.is_symlink():
            raise RuntimeFailure(f"transient-connect evidence is a symbolic link: {source}")
        if not source.exists():
            if required:
                raise RuntimeFailure(f"missing transient-connect evidence: {source}")
            continue
        source.rename(target)


def failed_marker_summary(game_dir: Path, role: str) -> str:
    """Return a bounded report summary suitable for the CI log when a harness marker is ``fail``."""
    report_path = game_dir / "e2e-report" / "report.json"
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        return f"{role}: report unavailable ({exc})"
    if not isinstance(report, dict):
        return f"{role}: malformed report root"
    steps = report.get("steps")
    if not isinstance(steps, list):
        return f"{role}: malformed report steps"
    failures: list[str] = []
    for step in steps:
        if not isinstance(step, dict) or step.get("status") == "pass":
            continue
        name = str(step.get("name", "<unnamed>"))
        status = str(step.get("status", "<missing>"))
        message = str(step.get("message", "")).replace("\n", " ")[:500]
        failures.append(f"{name}={status}: {message}")
    return f"{role}: " + ("; ".join(failures) if failures else "marker failed without a failed step")


def inspect_screenshot(
    path: Path, *, expected_format: str = "PNG"
) -> dict[str, Any]:
    """Decode an image and reject corrupt, implausible, or effectively blank evidence.

    Packaged-runtime callers retain the PNG-only default.  Protected Pages code may use the
    same pixel inspection for the WebP derivative that it creates from an already validated PNG.
    """

    if expected_format not in {"PNG", "WEBP"}:
        raise RuntimeFailure(f"unsupported screenshot format contract: {expected_format!r}")

    try:
        from PIL import Image, ImageStat, UnidentifiedImageError
    except ImportError as exc:  # pragma: no cover - CI installs the locked E2E requirements
        raise RuntimeFailure("Pillow is required for screenshot pixel validation") from exc

    try:
        Image.MAX_IMAGE_PIXELS = 20_000_000
        with Image.open(path) as image:
            if image.format != expected_format:
                raise RuntimeFailure(
                    f"screenshot is not a {expected_format} image: {path}"
                )
            width, height = image.size
            if width < 640 or height < 360 or width * height > Image.MAX_IMAGE_PIXELS:
                raise RuntimeFailure(
                    f"screenshot dimensions are implausible: {path} ({width}x{height})"
                )
            image.load()
            rgb = image.convert("RGB")
            sample = rgb.resize((160, 90), Image.Resampling.BILINEAR)
            luma = sample.convert("L")
            entropy = float(luma.entropy())
            channel_stddev = [float(value) for value in ImageStat.Stat(sample).stddev]
            palette_counts = sample.quantize(colors=32).getcolors() or []
            sample_pixels = sample.width * sample.height
            meaningful_colors = sum(
                count >= max(2, sample_pixels // 1000) for count, _ in palette_counts
            )
            luma_histogram = luma.histogram()
            dark_fraction = sum(luma_histogram[:8]) / sample_pixels
            light_fraction = sum(luma_histogram[248:]) / sample_pixels
            if (
                entropy < 0.75
                or max(channel_stddev) < 2.0
                or meaningful_colors < 4
                or dark_fraction > 0.98
                or light_fraction > 0.995
            ):
                raise RuntimeFailure(
                    f"screenshot is effectively blank: {path} "
                    f"(entropy={entropy:.3f}, colors={meaningful_colors}, "
                    f"dark={dark_fraction:.3f}, light={light_fraction:.3f})"
                )
            pixel_sha256 = hashlib.sha256(rgb.tobytes()).hexdigest()
    except RuntimeFailure:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise RuntimeFailure(f"screenshot cannot be decoded: {path}: {exc}") from exc

    return {
        "width": width,
        "height": height,
        "file_sha256": sha256(path),
        "pixel_sha256": pixel_sha256,
        "luma_entropy": round(entropy, 3),
        "meaningful_colors": meaningful_colors,
        "dark_fraction": round(dark_fraction, 4),
        "light_fraction": round(light_fraction, 4),
    }


def validate_opaque_stars_background(
    path: Path,
    probe: OpaqueStarsProbe | tuple[float, float, float, float],
) -> None:
    """Reject a bright or washed-out OPAQUE_STARS backdrop in a normalized UI-free region."""

    if not isinstance(probe, OpaqueStarsProbe):
        matches = [
            candidate
            for candidate in OPAQUE_STARS_PROBES.values()
            if candidate.region == probe
        ]
        if len(matches) != 1:
            raise RuntimeFailure(
                f"no unique OPAQUE_STARS probe owns background region {probe!r}"
            )
        probe = matches[0]
    region = probe.region
    try:
        from PIL import Image, UnidentifiedImageError
    except ImportError as exc:  # pragma: no cover - CI installs the locked E2E requirements
        raise RuntimeFailure("Pillow is required for screenshot pixel validation") from exc

    try:
        if (
            len(region) != 4
            or not all(0.0 <= coordinate <= 1.0 for coordinate in region)
            or region[0] >= region[2]
            or region[1] >= region[3]
        ):
            raise RuntimeFailure(f"invalid OPAQUE_STARS background region {region!r}")
        with Image.open(path) as image:
            width, height = image.size
            box = (
                int(region[0] * width),
                int(region[1] * height),
                int(region[2] * width),
                int(region[3] * height),
            )
            if box[0] >= box[2] or box[1] >= box[3]:
                raise RuntimeFailure(
                    f"OPAQUE_STARS background region {region!r} is empty at {width}x{height}"
                )
            luma = image.convert("RGB").crop(box).convert("L")
            histogram = luma.histogram()
            pixels = luma.width * luma.height
            mean_luma = sum(value * count for value, count in enumerate(histogram)) / pixels
            bright_fraction = sum(histogram[probe.bright_luma :]) / pixels
    except RuntimeFailure:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise RuntimeFailure(
            f"cannot inspect OPAQUE_STARS background in screenshot {path}: {exc}"
        ) from exc

    if (
        mean_luma > probe.maximum_mean_luma
        or bright_fraction > probe.maximum_bright_fraction
    ):
        raise RuntimeFailure(
            f"OPAQUE_STARS background is unexpectedly bright or washed out in {path} "
            f"region={region!r} (mean_luma={mean_luma:.2f}, "
            f"fraction_luma_gte_{probe.bright_luma}={bright_fraction:.3f}; "
            f"required mean_luma<={probe.maximum_mean_luma:.2f} and "
            f"bright_fraction<={probe.maximum_bright_fraction:.3f})"
        )


def validate_required_gui_text(
    path: Path, scenario: str, role: str, step: str
) -> None:
    """Require stable bright glyph pixels in GUI regions whose copy must remain readable."""

    probes = REQUIRED_GUI_TEXT_PROBES.get((scenario, role, step))
    if probes is None:
        return

    try:
        from PIL import Image, UnidentifiedImageError
    except ImportError as exc:  # pragma: no cover - CI installs the locked E2E requirements
        raise RuntimeFailure("Pillow is required for screenshot pixel validation") from exc

    try:
        with Image.open(path) as image:
            rgb = image.convert("RGB")
            if rgb.size != GUI_TEXT_REFERENCE_SIZE:
                rgb = rgb.resize(GUI_TEXT_REFERENCE_SIZE, Image.Resampling.LANCZOS)
            for label, box, minimum_luma_exclusive, minimum_pixels in probes:
                left, top, right, bottom = box
                if (
                    left < 0
                    or top < 0
                    or right > rgb.width
                    or bottom > rgb.height
                    or left >= right
                    or top >= bottom
                ):
                    raise RuntimeFailure(f"invalid required GUI text region {label!r}: {box!r}")
                histogram = rgb.crop(box).convert("L").histogram()
                matching_pixels = sum(histogram[minimum_luma_exclusive + 1 :])
                if matching_pixels < minimum_pixels:
                    raise RuntimeFailure(
                        f"required GUI text is missing or unreadable in {path}: {label} "
                        f"region={box!r}, pixels_luma_gt_{minimum_luma_exclusive}="
                        f"{matching_pixels}, required>={minimum_pixels}"
                    )
    except RuntimeFailure:
        raise
    except (OSError, UnidentifiedImageError, ValueError) as exc:
        raise RuntimeFailure(f"cannot inspect required GUI text in screenshot {path}: {exc}") from exc


def inspect_screenshot_for_step(
    path: Path, scenario: str, role: str, step: str
) -> dict[str, Any]:
    """Apply generic image checks plus any semantic pixel contract owned by this report step."""

    metrics = inspect_screenshot(path)
    opaque_stars_probe = OPAQUE_STARS_PROBES.get((scenario, role, step))
    if opaque_stars_probe is not None:
        validate_opaque_stars_background(path, opaque_stars_probe)
    validate_required_gui_text(path, scenario, role, step)
    return metrics


def compare_screenshots(
    first: Path,
    second: Path,
    minimum_changed_fraction: float,
    region: tuple[float, float, float, float] | None = None,
) -> dict[str, Any]:
    try:
        from PIL import Image, ImageChops
    except ImportError as exc:  # pragma: no cover - CI installs the locked E2E requirements
        raise RuntimeFailure("Pillow is required for screenshot pixel validation") from exc

    try:
        with Image.open(first) as first_image, Image.open(second) as second_image:
            first_rgb = first_image.convert("RGB")
            second_rgb = second_image.convert("RGB")
            if first_rgb.size != second_rgb.size:
                raise RuntimeFailure(
                    f"screenshots changed dimensions unexpectedly: {first} {first_rgb.size}, "
                    f"{second} {second_rgb.size}"
                )
            if region is not None:
                width, height = first_rgb.size
                left, top, right, bottom = region
                box = (
                    int(left * width),
                    int(top * height),
                    int(right * width),
                    int(bottom * height),
                )
                if box[0] >= box[2] or box[1] >= box[3]:
                    raise RuntimeFailure(
                        f"comparison region {region} is empty at {width}x{height}"
                    )
                first_rgb = first_rgb.crop(box)
                second_rgb = second_rgb.crop(box)
            difference = ImageChops.difference(first_rgb, second_rgb).convert("L")
            histogram = difference.histogram()
            pixels = difference.width * difference.height
            changed_fraction = sum(histogram[8:]) / pixels
            rms_difference = (
                sum(value * value * count for value, count in enumerate(histogram)) / pixels
            ) ** 0.5
    except RuntimeFailure:
        raise
    except (OSError, ValueError) as exc:
        raise RuntimeFailure(f"cannot compare screenshots {first} and {second}: {exc}") from exc

    if changed_fraction < minimum_changed_fraction:
        scope = "in region " + repr(region) if region is not None else "over the frame"
        raise RuntimeFailure(
            f"screenshots expected to change did not change enough {scope}: {first} -> {second} "
            f"(changed={changed_fraction:.7f}, required={minimum_changed_fraction:.7f})"
        )
    comparison: dict[str, Any] = {
        "changed_fraction": round(changed_fraction, 7),
        "rms_difference": round(rms_difference, 3),
        "required_changed_fraction": minimum_changed_fraction,
    }
    if region is not None:
        comparison["region"] = list(region)
    return comparison


def validate_report(game_dir: Path, row: dict[str, Any], scenario: str, role: str) -> dict[str, Any]:
    report_path = game_dir / "e2e-report" / "report.json"
    if not report_path.is_file():
        raise RuntimeFailure(f"missing {role} report: {report_path}")
    try:
        report = json.loads(report_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise RuntimeFailure(f"unreadable {role} report: {exc}") from exc
    try:
        role_contract = SCENARIO_CONTRACT.role(scenario, role)
    except ValueError as exc:
        raise RuntimeFailure(f"no locked report contract for {scenario}/{role}") from exc
    expected_steps = list(role_contract.step_ids)
    if report.get("contract_sha256") != SCENARIO_CONTRACT.sha256:
        raise RuntimeFailure(
            f"{role} report scenario contract hash mismatch: "
            f"{report.get('contract_sha256')!r} != {SCENARIO_CONTRACT.sha256!r}"
        )
    if report.get("version") != row["runtime_version"]:
        raise RuntimeFailure(f"{role} report runtime version mismatch")
    if report.get("role") != role or report.get("scenario") != scenario:
        raise RuntimeFailure(f"{role} report identity mismatch")
    steps = report.get("steps")
    if not isinstance(steps, list) or any(not isinstance(step, dict) for step in steps):
        raise RuntimeFailure(f"{role} report steps must be an array of objects")
    actual_steps = [step.get("name") for step in steps]
    if actual_steps != expected_steps:
        raise RuntimeFailure(
            f"{role} step contract mismatch: expected {expected_steps}, got "
            f"{actual_steps}"
        )
    if report.get("status") != "pass":
        raise RuntimeFailure(f"{role} report contains a failed/timed-out step")
    screenshot_paths: dict[str, Path] = {}
    screenshot_validation: dict[str, dict[str, Any]] = {}
    for step_contract, step in zip(role_contract.steps, steps, strict=True):
        if step_contract.assertion_required and step.get("status") != "pass":
            raise RuntimeFailure(
                f"{role}/{step_contract.id} required assertion did not pass"
            )
        screenshot = step.get("screenshot")
        capture_required = step_contract.capture is not None
        if capture_required:
            if not isinstance(screenshot, str) or not screenshot:
                raise RuntimeFailure(
                    f"{role}/{step_contract.id} omitted its required screenshot"
                )
        elif screenshot is not None:
            raise RuntimeFailure(
                f"{role}/{step_contract.id} produced an unexpected screenshot"
            )
        if capture_required:
            screenshots_root = (game_dir / "screenshots").resolve()
            screenshot_path = (screenshots_root / screenshot).resolve()
            if screenshots_root not in screenshot_path.parents:
                raise RuntimeFailure(
                    f"{role}/{step_contract.id} screenshot escapes its profile"
                )
            screenshot_paths[step_contract.id] = screenshot_path
            screenshot_validation[step_contract.id] = inspect_screenshot_for_step(
                screenshot_path, scenario, role, step_contract.id
            )
    pair_validation: dict[str, dict[str, Any]] = {}
    for comparison in role_contract.comparisons:
        first_step = comparison.first_step
        second_step = comparison.second_step
        if first_step not in screenshot_paths or second_step not in screenshot_paths:
            raise RuntimeFailure(
                f"pixel comparison contract is missing {role}/{first_step} or {role}/{second_step}"
            )
        pair_validation[f"{first_step}->{second_step}"] = compare_screenshots(
            screenshot_paths[first_step],
            screenshot_paths[second_step],
            comparison.minimum_changed_fraction,
            comparison.region,
        )
    report["pixel_validation"] = {
        "screenshots": screenshot_validation,
        "comparisons": pair_validation,
    }
    return report


def is_benign_kqueue_debug_appender_line(
    lines: list[str], line_index: int
) -> bool:
    """Recognize NeoForge's harmless Linux kqueue-probe logging recursion.

    NeoForge's DebugFile throwable renderer can initialize Netty's failed macOS/BSD
    native probe a second time while formatting a DEBUG message. Ignore only that
    exact linkage error inside the matching appender stack and only when its original
    unsupported-platform cause is present; every other linkage error remains fatal.
    """

    if KQUEUE_NATIVE_INIT_FAILURE not in lines[line_index]:
        return False
    first_candidate = max(0, line_index - DEBUG_FILE_APPENDER_STACK_WINDOW)
    for header_index in range(line_index, first_candidate - 1, -1):
        if DEBUG_FILE_APPENDER_FAILURE not in lines[header_index]:
            continue
        block = lines[
            header_index : min(
                len(lines), header_index + DEBUG_FILE_APPENDER_STACK_WINDOW
            )
        ]
        return any(
            KQUEUE_UNSUPPORTED_PLATFORM_CAUSE in candidate for candidate in block
        )
    return False


def scan_runtime_logs(
    logs: list[Path], *, require_client_pass: bool = True
) -> None:
    hits: list[str] = []
    for log in logs:
        if not log.is_file():
            raise RuntimeFailure(f"runtime log missing: {log}")
        content = log.read_text(encoding="utf-8", errors="replace")
        if (
            require_client_pass
            and "client" in log.stem.lower()
            and "[QS-E2E] FINISHED status=pass" not in content
        ):
            hits.append(f"{log}: missing [QS-E2E] FINISHED status=pass")
        lines = content.splitlines()
        for line_index, line in enumerate(lines):
            if any(pattern.search(line) for pattern in FATAL_LOG_PATTERNS):
                if is_benign_kqueue_debug_appender_line(lines, line_index):
                    continue
                hits.append(f"{log}:{line_index + 1}: {line[:300]}")
    if hits:
        raise RuntimeFailure("fatal runtime log evidence:\n" + "\n".join(hits[:30]))


def require_compatibility_marker(logs: list[Path], row: Mapping[str, Any]) -> None:
    patch = row.get("compatibility_patch")
    if patch is None:
        return
    marker = COMPATIBILITY_LOG_MARKERS.get(patch)
    if marker is None:
        raise RuntimeFailure(f"unknown runtime compatibility patch {patch!r}")
    missing: list[str] = []
    for log in logs:
        try:
            content = log.read_text(encoding="utf-8", errors="replace")
        except OSError as exc:
            raise RuntimeFailure(f"cannot read compatibility log {log}: {exc}") from exc
        if marker not in content:
            missing.append(str(log))
    if missing:
        raise RuntimeFailure(
            f"compatibility patch {patch!r} was not observed in every process: {missing}"
        )


def artifact_record(manifest: dict[str, Any], node: str) -> dict[str, Any]:
    records = [record for record in manifest.get("artifacts", []) if record.get("artifact_node") == node]
    if len(records) != 1:
        raise RuntimeFailure(f"artifact manifest has {len(records)} records for {node}")
    return records[0]


def _evidence_files(
    source: Path,
    *,
    recursive: bool,
    allowed: Callable[[Path], bool],
) -> list[Path]:
    try:
        source_stat = source.lstat()
    except FileNotFoundError:
        return []
    if stat.S_ISLNK(source_stat.st_mode) or not stat.S_ISDIR(source_stat.st_mode):
        raise RuntimeFailure(f"evidence source is not a real directory: {source}")
    candidates = source.rglob("*") if recursive else source.iterdir()
    files: list[Path] = []
    for path in sorted(candidates):
        path_stat = path.lstat()
        relative = path.relative_to(source)
        if stat.S_ISLNK(path_stat.st_mode):
            raise RuntimeFailure(f"evidence source contains a symbolic link: {path}")
        if stat.S_ISDIR(path_stat.st_mode):
            if not recursive:
                raise RuntimeFailure(f"evidence source contains an unexpected directory: {path}")
            continue
        if not stat.S_ISREG(path_stat.st_mode) or not allowed(relative):
            raise RuntimeFailure(f"evidence source contains an unapproved file: {path}")
        files.append(path)
    return files


def _copy_bounded_evidence_file(
    source: Path,
    destination: Path,
    *,
    maximum_bytes: int,
) -> int:
    source_stat = source.lstat()
    if stat.S_ISLNK(source_stat.st_mode) or not stat.S_ISREG(source_stat.st_mode):
        raise RuntimeFailure(f"evidence file is not a regular file: {source}")
    if source_stat.st_size > maximum_bytes:
        raise RuntimeFailure(
            f"evidence file exceeds {maximum_bytes} bytes: {source} ({source_stat.st_size})"
        )
    flags = os.O_RDONLY | getattr(os, "O_BINARY", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(source, flags)
    try:
        opened_stat = os.fstat(descriptor)
        if (
            not stat.S_ISREG(opened_stat.st_mode)
            or opened_stat.st_dev != source_stat.st_dev
            or opened_stat.st_ino != source_stat.st_ino
            or opened_stat.st_size != source_stat.st_size
        ):
            raise RuntimeFailure(f"evidence file changed while opening: {source}")
        destination.parent.mkdir(parents=True, exist_ok=True)
        copied = 0
        with os.fdopen(descriptor, "rb") as input_stream, destination.open("xb") as output:
            descriptor = -1
            for chunk in iter(lambda: input_stream.read(1024 * 1024), b""):
                copied += len(chunk)
                if copied > maximum_bytes:
                    raise RuntimeFailure(f"evidence file grew while copying: {source}")
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        if copied != source_stat.st_size:
            raise RuntimeFailure(f"evidence file changed while copying: {source}")
        os.chmod(destination, 0o644)
        return copied
    finally:
        if descriptor >= 0:
            os.close(descriptor)


def export_profile_evidence(
    execution_profile: Path,
    evidence_profile: Path,
    result: dict[str, Any],
    *,
    include_runtime_files: bool = True,
) -> Path:
    """Atomically export only bounded evidence, never runtime/install directories."""

    if evidence_profile.exists() or evidence_profile.is_symlink():
        raise RuntimeFailure(f"evidence profile already exists: {evidence_profile}")
    evidence_profile.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(
        tempfile.mkdtemp(prefix=f".{evidence_profile.name}.exporting-", dir=evidence_profile.parent)
    )
    copied_files = 0
    copied_bytes = 0

    def copy_group(
        source: Path,
        destination: Path,
        *,
        recursive: bool,
        allowed: Callable[[Path], bool],
        maximum_bytes: int,
    ) -> None:
        nonlocal copied_files, copied_bytes
        for path in _evidence_files(source, recursive=recursive, allowed=allowed):
            relative = path.relative_to(source)
            copied_files += 1
            if copied_files > MAX_EVIDENCE_FILES:
                raise RuntimeFailure("evidence export exceeds its file-count limit")
            copied_bytes += _copy_bounded_evidence_file(
                path,
                staging / destination / relative,
                maximum_bytes=maximum_bytes,
            )
            if copied_bytes > MAX_EVIDENCE_TOTAL_BYTES:
                raise RuntimeFailure("evidence export exceeds its total-byte limit")

    try:
        if include_runtime_files:
            copy_group(
                execution_profile / "logs",
                Path("logs"),
                recursive=False,
                allowed=lambda path: len(path.parts) == 1 and path.suffix == ".log",
                maximum_bytes=MAX_EVIDENCE_LOG_BYTES,
            )
            for owner in ("client_a", "client_b"):
                copy_group(
                    execution_profile / owner / "e2e-report",
                    Path(owner) / "e2e-report",
                    recursive=False,
                    allowed=lambda path: path.as_posix() in {"report.json", "done.marker"},
                    maximum_bytes=MAX_EVIDENCE_REPORT_BYTES,
                )
                copy_group(
                    execution_profile / owner / "screenshots",
                    Path(owner) / "screenshots",
                    recursive=False,
                    allowed=lambda path: len(path.parts) == 1 and path.suffix == ".png",
                    maximum_bytes=MAX_EVIDENCE_SCREENSHOT_BYTES,
                )
                copy_group(
                    execution_profile / owner / "crash-reports",
                    Path(owner) / "crash-reports",
                    recursive=True,
                    allowed=lambda path: path.suffix == ".txt",
                    maximum_bytes=MAX_EVIDENCE_CRASH_REPORT_BYTES,
                )
                retry_diagnostic = (
                    execution_profile
                    / "diagnostics"
                    / "transient-connect-attempt-1"
                    / owner
                )
                copy_group(
                    retry_diagnostic / "logs",
                    Path("diagnostics")
                    / "transient-connect-attempt-1"
                    / owner
                    / "logs",
                    recursive=False,
                    allowed=lambda path: path.as_posix() == "client.log",
                    maximum_bytes=MAX_EVIDENCE_LOG_BYTES,
                )
                copy_group(
                    retry_diagnostic / "e2e-report",
                    Path("diagnostics")
                    / "transient-connect-attempt-1"
                    / owner
                    / "e2e-report",
                    recursive=False,
                    allowed=lambda path: path.as_posix() in {"report.json", "done.marker"},
                    maximum_bytes=MAX_EVIDENCE_REPORT_BYTES,
                )
                copy_group(
                    retry_diagnostic / "screenshots",
                    Path("diagnostics")
                    / "transient-connect-attempt-1"
                    / owner
                    / "screenshots",
                    recursive=False,
                    allowed=lambda path: len(path.parts) == 1 and path.suffix == ".png",
                    maximum_bytes=MAX_EVIDENCE_SCREENSHOT_BYTES,
                )
            copy_group(
                execution_profile / "server" / "crash-reports",
                Path("server") / "crash-reports",
                recursive=True,
                allowed=lambda path: path.suffix == ".txt",
                maximum_bytes=MAX_EVIDENCE_CRASH_REPORT_BYTES,
            )
        result_bytes = (json.dumps(result, indent=2) + "\n").encode("utf-8")
        if len(result_bytes) > MAX_EVIDENCE_REPORT_BYTES:
            raise RuntimeFailure("packaged result exceeds its evidence size limit")
        result_path = staging / "result.json"
        with result_path.open("xb") as output:
            output.write(result_bytes)
            output.flush()
            os.fsync(output.fileno())
        os.chmod(result_path, 0o644)
        if evidence_profile.exists() or evidence_profile.is_symlink():
            raise RuntimeFailure(f"evidence profile appeared during export: {evidence_profile}")
        staging.rename(evidence_profile)
        return evidence_profile / "result.json"
    finally:
        if staging.exists() and not staging.is_symlink():
            shutil.rmtree(staging)
        else:
            staging.unlink(missing_ok=True)


def run_packaged_row(
    repo: Path,
    matrix: dict[str, Any],
    row: dict[str, Any],
    scenario: str,
    manifest: dict[str, Any],
    manifest_path: Path,
    output_root: Path,
    runtime_session: PackagedRuntimeSession,
    *,
    compatibility_lane: CompatibilityLane | None = None,
    compatibility_files: tuple[Path, ...] = (),
) -> dict[str, Any]:
    port = allocate_port()
    compatibility_suffix = (
        f"--{compatibility_lane.mod.id}" if compatibility_lane is not None else ""
    )
    identity = safe_id(
        f"{row['artifact_node']}--{row['runtime_version']}"
        f"{compatibility_suffix}--{scenario}"
    )
    profile = runtime_session.scenario_profile(identity)
    profiles_root = output_root / "profiles"
    evidence_profile = profiles_root / identity
    profiles_root.mkdir(parents=True, exist_ok=True)
    if evidence_profile.exists() or evidence_profile.is_symlink():
        raise RuntimeFailure(f"evidence profile is not fresh: {evidence_profile}")

    result: dict[str, Any] = {
        "artifact_node": row["artifact_node"],
        "runtime_version": row["runtime_version"],
        "loader": row["loader"],
        "scenario": scenario,
        "contract_sha256": SCENARIO_CONTRACT.sha256,
        "jar_sha256": None,
        "port": port,
        "status": "fail",
        "profile": evidence_profile.relative_to(output_root).as_posix(),
    }
    if compatibility_lane is not None:
        result["compatibility"] = compatibility_lane.public_identity()
        result["installed_compatibility"] = []
    started = time.monotonic()
    processes: list[subprocess.Popen[bytes]] = []
    handles: list[BinaryIO] = []
    runtime_logs: list[Path] = []
    try:
        record = artifact_record(manifest, row["artifact_node"])
        if record["loader"] != row["loader"]:
            raise RuntimeFailure("artifact manifest loader mismatch")
        result["jar_sha256"] = record["sha256"]
        stage = manifest_path.parent
        release_jar = stage / record["path"]
        harness_jar = stage / record["harness"]["path"]
        if sha256(release_jar) != record["sha256"]:
            raise RuntimeFailure(f"fan-in artifact hash mismatch: {release_jar}")
        if sha256(harness_jar) != record["harness"]["sha256"]:
            raise RuntimeFailure(f"fan-in harness hash mismatch: {harness_jar}")

        java = java_executable(int(row["java"]))
        orchestration = SCENARIO_CONTRACT.orchestration_for(scenario)
        roles = SCENARIO_CONTRACT.expected_roles(scenario)
        client_directories = {
            "client_a": profile / "client_a",
            "client_b": profile / "client_b",
        }
        client_names = {"client_a": "Alice", "client_b": "Bob"}
        server = profile / "server"
        for directory in (server, *(client_directories[role] for role in roles)):
            directory.mkdir(parents=True)

        server_install_log = profile / "logs" / "server-install.log"
        with runtime_dependencies(
            row,
            runtime_session.store,
            repo / "gradle" / "verification-metadata.xml",
        ) as dependencies:
            server_command = prepare_server(
                matrix,
                row,
                server,
                runtime_session.store,
                java,
                server_install_log,
            )
            write_server_files(server, port, repo / "e2e" / "server-template")
            install_dir, version_id = prepare_client_install(
                matrix, row, runtime_session, java
            )

            installed_quickskin: list[dict[str, str]] = []
            for game_dir in (server, *(client_directories[role] for role in roles)):
                destination = copy_verified(
                    release_jar, game_dir / "mods", record["sha256"]
                )
                installed_quickskin.append(
                    {
                        "path": destination.relative_to(profile).as_posix(),
                        "sha256": sha256(destination),
                    }
                )
                for dependency in dependencies:
                    copy_verified(
                        dependency.path,
                        game_dir / "mods",
                        dependency.sha256,
                        name=dependency.filename,
                    )
            for game_dir in (client_directories[role] for role in roles):
                copy_verified(
                    harness_jar,
                    game_dir / "mods",
                    record["harness"]["sha256"],
                )
                shutil.copy2(
                    repo / "e2e" / "options.txt.template", game_dir / "options.txt"
                )
                write_e2e_client_config(game_dir)
                if compatibility_lane is not None:
                    write_compatibility_client_config(
                        game_dir, compatibility_lane.mod.id
                    )
                if row["loader"] == "neoforge":
                    shutil.copy2(
                        repo / "e2e" / "fml.toml.neoforge",
                        game_dir / "config" / "fml.toml",
                    )
            if compatibility_lane is not None:
                locked_by_name = {
                    item.filename: item for item in compatibility_lane.artifact.files
                }
                supplied_by_name = {item.name: item for item in compatibility_files}
                if set(locked_by_name) != set(supplied_by_name):
                    raise RuntimeFailure(
                        "materialized compatibility files disagree with the locked artifact"
                    )
                install_roots = [client_directories[role] for role in roles]
                if compatibility_lane.mod.install_on == "client-and-server":
                    install_roots.insert(0, server)
                installed_compatibility: list[dict[str, str]] = []
                for game_dir in install_roots:
                    for filename, locked in locked_by_name.items():
                        source = supplied_by_name[filename]
                        expected_destination = game_dir / "mods" / locked.filename
                        if expected_destination.exists() or expected_destination.is_symlink():
                            raise RuntimeFailure(
                                "compatibility artifact filename collides with an installed JAR: "
                                f"{locked.filename}"
                            )
                        destination = copy_verified(
                            source,
                            game_dir / "mods",
                            locked.sha256,
                            name=locked.filename,
                        )
                        installed_compatibility.append(
                            {
                                "path": destination.relative_to(profile).as_posix(),
                                "sha256": sha256(destination),
                            }
                        )
                result["installed_compatibility"] = installed_compatibility
        result["installed_quickskin"] = installed_quickskin

        env = process_env(java)
        server_log = profile / "logs" / "server.log"
        server_process, server_handle = start_process(server_command, server, server_log, env)
        processes.append(server_process)
        handles.append(server_handle)
        runtime_logs.append(server_log)
        wait_for_log(server_process, server_log, "Done (", timeout=1200)

        client_processes: dict[str, subprocess.Popen[bytes]] = {}
        client_handles: dict[str, BinaryIO] = {}
        client_logs: dict[str, Path] = {}
        client_deadlines: dict[str, float] = {}
        replaymod_login_diagnostics: dict[str, ReplayModLoginDiagnostic] = {}
        transient_retry_used = False

        def launch_client(role: str) -> None:
            game_dir = client_directories[role]
            client_log = profile / "logs" / f"{role}.log"
            command = client_command(
                install_dir,
                version_id,
                game_dir,
                row,
                scenario,
                role,
                client_names[role],
                port,
                java,
                compatibility_lane.mod.id if compatibility_lane is not None else None,
            )
            process, handle = start_process(command, game_dir, client_log, env)
            client_processes[role] = process
            client_handles[role] = handle
            client_logs[role] = client_log
            client_deadlines[role] = time.monotonic() + 600
            if (
                compatibility_lane is not None
                and compatibility_lane.mod.id == "replaymod"
            ):
                replaymod_login_diagnostics[role] = ReplayModLoginDiagnostic()
            processes.append(process)
            handles.append(handle)
            runtime_logs.append(client_log)

        def capture_replaymod_login_stall(role: str) -> None:
            diagnostic = replaymod_login_diagnostics.get(role)
            if diagnostic is None:
                return
            maybe_capture_replaymod_login_stall(
                client_processes[role],
                client_logs[role],
                diagnostic,
                server_process=server_process,
                server_log=server_log,
            )

        def retry_transient_connection(role: str, marker: str) -> str | None:
            nonlocal transient_retry_used
            if (
                marker != "fail"
                or compatibility_lane is None
                or transient_retry_used
                or not is_transient_connect_timeout_report(
                    client_directories[role], row, scenario, role
                )
            ):
                return marker

            transient_retry_used = True
            print(
                f"Transient vanilla login timeout for {role}; preserving attempt 1 and "
                "retrying this client once.",
                flush=True,
            )
            stop_process(client_processes[role])
            client_handles[role].close()
            failed_log = client_logs[role]
            # A timeout category does not excuse a simultaneous loader, mixin, linkage, or crash
            # signature. Check the failed attempt before removing it from the final pass-only scan.
            scan_runtime_logs([failed_log], require_client_pass=False)
            archive_transient_connect_attempt(
                profile, client_directories[role], failed_log, role
            )
            runtime_logs.remove(failed_log)
            launch_client(role)
            return None

        def await_role(role: str) -> str:
            while True:
                marker = wait_for_marker(
                    client_processes[role],
                    client_directories[role],
                    role,
                    on_poll=lambda: capture_replaymod_login_stall(role),
                )
                resolved = retry_transient_connection(role, marker)
                if resolved is not None:
                    return resolved

        def await_server_join(role: str, text: str, timeout: int) -> None:
            """Wait for the first concurrent client without hiding an early harness failure."""

            deadline = time.monotonic() + timeout
            while True:
                content = (
                    server_log.read_text(encoding="utf-8", errors="replace")
                    if server_log.exists()
                    else ""
                )
                if text in content:
                    return
                marker = marker_value(client_directories[role], role)
                if marker is not None:
                    resolved = retry_transient_connection(role, marker)
                    if resolved is None:
                        deadline = time.monotonic() + timeout
                        continue
                    raise RuntimeFailure(
                        f"{role} failed before server marker {text!r}; "
                        f"{failed_marker_summary(client_directories[role], role)}"
                    )
                if client_processes[role].poll() is not None:
                    raise RuntimeFailure(
                        f"{role} exited before server marker {text!r}; see {server_log}"
                    )
                capture_replaymod_login_stall(role)
                if time.monotonic() >= deadline:
                    raise RuntimeFailure(
                        f"timed out waiting for server marker {text!r}; see {server_log}"
                    )
                time.sleep(2)

        def await_concurrent_roles(concurrent_roles: tuple[str, ...]) -> dict[str, str]:
            completed: dict[str, str] = {}
            pending = set(concurrent_roles)
            while pending:
                now = time.monotonic()
                for role in tuple(pending):
                    marker = marker_value(client_directories[role], role)
                    if marker is not None:
                        resolved = retry_transient_connection(role, marker)
                        if resolved is not None:
                            completed[role] = resolved
                            pending.remove(role)
                        continue
                    if client_processes[role].poll() is not None:
                        raise RuntimeFailure(
                            f"{role} exited before writing "
                            f"{client_directories[role] / 'e2e-report' / 'done.marker'}"
                        )
                    capture_replaymod_login_stall(role)
                    if now >= client_deadlines[role]:
                        raise RuntimeFailure(
                            f"timed out waiting for {role} marker "
                            f"{client_directories[role] / 'e2e-report' / 'done.marker'}"
                        )
                if pending:
                    time.sleep(2)
            return completed

        markers: dict[str, str] = {}
        if orchestration.mode == "single-client":
            launch_client(roles[0])
            markers[roles[0]] = await_role(roles[0])
        elif orchestration.mode == "sequential-two-client":
            for role in orchestration.role_order:
                launch_client(role)
                markers[role] = await_role(role)
        elif orchestration.mode == "concurrent-two-client":
            pending = list(roles)
            if orchestration.start_after is not None:
                first_role = orchestration.start_after.role
                launch_client(first_role)
                pending.remove(first_role)
                await_server_join(
                    first_role,
                    orchestration.start_after.server_log_marker,
                    orchestration.start_after.timeout_seconds,
                )
            for role in pending:
                launch_client(role)
            markers.update(await_concurrent_roles(roles))
        else:  # pragma: no cover - scenario_contract rejects unknown modes
            raise RuntimeFailure(
                f"unsupported E2E orchestration mode {orchestration.mode!r}"
            )

        failed_roles = [role for role in roles if markers.get(role) != "pass"]
        if failed_roles:
            summaries = [
                failed_marker_summary(client_directories[role], role)
                for role in failed_roles
            ]
            details = "; ".join(summaries)
            raise RuntimeFailure(
                "harness marker failure: "
                + ", ".join(f"{role}={markers.get(role)!r}" for role in roles)
                + (f"; {details}" if details else "")
            )
        reports = {
            role: validate_report(
                client_directories[role],
                row,
                scenario,
                role,
            )
            for role in roles
        }
        scan_runtime_logs(runtime_logs)
        require_compatibility_marker(runtime_logs, row)
        crash_reports = list(profile.rglob("crash-reports/*.txt"))
        if crash_reports:
            raise RuntimeFailure(f"runtime produced crash reports: {crash_reports}")
        result["reports"] = reports
        result["status"] = "pass"
    except Exception as exc:
        result["error"] = str(exc)
    finally:
        for process in reversed(processes):
            stop_process(process)
        for handle in handles:
            handle.close()
        result["elapsed_s"] = round(time.monotonic() - started, 1)
        try:
            export_profile_evidence(profile, evidence_profile, result)
        except Exception as export_error:
            result["status"] = "fail"
            previous_error = result.get("error")
            result["error"] = (
                f"{previous_error}; evidence export failed: {export_error}"
                if previous_error
                else f"evidence export failed: {export_error}"
            )
            try:
                export_profile_evidence(
                    profile,
                    evidence_profile,
                    result,
                    include_runtime_files=False,
                )
            except Exception:
                pass
    return result
