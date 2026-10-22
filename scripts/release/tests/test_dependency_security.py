from __future__ import annotations

import hashlib
import json
import re
import subprocess
import unittest
import xml.etree.ElementTree as ElementTree
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
VERIFICATION_FILE = ROOT / "gradle" / "verification-metadata.xml"
SHA256 = re.compile(r"[0-9a-f]{64}")
EXPECTED_LOCAL_TRUST = {
    (r"^remapped[.].+$", None),
    (r"^loom$", r"^mappings$"),
    (
        r"^net[.]minecraft$",
        r"^(minecraft-merged-[0-9a-f]{10}|(?:forge|neoforge)-[0-9A-Za-z.+_-]+-minecraft-merged(?:-deobf|-[0-9a-f]{10})?)$",
    ),
    (r"^net[.]minecraftforge[.][0-9a-f]{64}$", r"^fmlloader$"),
    (r"^net[.]neoforged[.]fancymodloader[.][0-9a-f]{64}$", r"^loader$"),
}


def _verification_tree() -> tuple[ElementTree.Element, dict[str, str]]:
    root = ElementTree.parse(VERIFICATION_FILE).getroot()
    namespace = {"v": root.tag.removeprefix("{").split("}", 1)[0]}
    return root, namespace


def _matches_trust(
    rule: ElementTree.Element,
    group: str,
    name: str,
) -> bool:
    regex = rule.get("regex") == "true"

    def matches(attribute: str, value: str) -> bool:
        expected = rule.get(attribute)
        if expected is None:
            return True
        return re.fullmatch(expected, value) is not None if regex else expected == value

    return matches("group", group) and matches("name", name)


class DependencySecurityPolicyTest(unittest.TestCase):
    def test_wrapper_and_global_mode_are_strictly_pinned(self) -> None:
        properties = (ROOT / "gradle.properties").read_text(encoding="utf-8")
        # The shared Build gate treats every external executable dependency as hash-enforced, so
        # exactly one mode line selects strict and no other mode survives beside it. An upstream
        # republication is a reviewed also-trust entry, never a relaxed mode.
        modes = re.findall(
            r"^org[.]gradle[.]dependency[.]verification=(.*)$", properties, re.MULTILINE
        )
        self.assertEqual(modes, ["strict"])
        self.assertIn("org.gradle.dependency.verification.console=verbose", properties)

        wrapper_properties = (
            ROOT / "gradle" / "wrapper" / "gradle-wrapper.properties"
        ).read_text(encoding="utf-8")
        distribution_hash = re.search(
            r"^distributionSha256Sum=([0-9a-f]{64})$",
            wrapper_properties,
            re.MULTILINE,
        )
        self.assertIsNotNone(distribution_hash)
        assert distribution_hash is not None
        distribution_sidecar = (
            ROOT / "gradle" / "wrapper" / "gradle-9.8.1-bin.zip.sha256"
        ).read_text(encoding="utf-8").strip()
        self.assertEqual(distribution_hash.group(1), distribution_sidecar)

        wrapper_jar = ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar"
        expected_jar_hash = (
            ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar.sha256"
        ).read_text(encoding="utf-8").split()[0]
        self.assertEqual(hashlib.sha256(wrapper_jar.read_bytes()).hexdigest(), expected_jar_hash)

    def test_windows_wrapper_crlf_survives_the_port_whitespace_gate(self) -> None:
        """A wrapper bump must not fail a staged whitespace gate on its own line endings.

        Gradle ships gradlew.bat with CRLF, as a Windows batch file must have. Git reads a bare
        carriage return as a trailing space, so without an attribute every changed line of that
        file is an error under `git diff --check`.

        Nothing else pins this. An ordinary `git diff --check` on a wrapper bump compares within
        the file's existing line endings and never sees the added CRLF lines, which is why the
        9.7.1 bump stayed green on its own Build gate and still aborted the 1.20.1 port while
        packaging, blocking the wave to every other release branch (ec1975e2).

        Schema 3 retired automatic ports, but the gate is still live: `ai_patch_policy` rejects
        an AI patch that fails `git diff --check`, and the schema-2 port controllers are retained
        for recovery.
        """
        wrapper_bat = ROOT / "gradlew.bat"
        self.assertIn(b"\r\n", wrapper_bat.read_bytes())

        attribute = subprocess.run(
            ("git", "check-attr", "whitespace", "--", "gradlew.bat"),
            cwd=ROOT,
            capture_output=True,
            text=True,
            check=True,
        ).stdout
        self.assertIn("cr-at-eol", attribute)

    def test_verification_is_strict_sha256_without_weak_hashes(self) -> None:
        root, namespace = _verification_tree()
        configuration = root.find("v:configuration", namespace)
        self.assertIsNotNone(configuration)
        assert configuration is not None
        self.assertEqual(configuration.findtext("v:verify-metadata", namespaces=namespace), "true")
        self.assertEqual(
            configuration.findtext("v:verify-signatures", namespaces=namespace),
            "false",
        )
        self.assertEqual(root.findall(".//v:sha1", namespace), [])
        self.assertEqual(root.findall(".//v:md5", namespace), [])

        components = root.findall("v:components/v:component", namespace)
        self.assertGreater(len(components), 300)
        coordinates: set[tuple[str, str, str]] = set()
        for component in components:
            coordinate = (
                component.attrib["group"],
                component.attrib["name"],
                component.attrib["version"],
            )
            self.assertNotIn(coordinate, coordinates)
            coordinates.add(coordinate)
            artifacts = component.findall("v:artifact", namespace)
            self.assertTrue(artifacts, coordinate)
            for artifact in artifacts:
                hashes = artifact.findall("v:sha256", namespace)
                self.assertEqual(len(hashes), 1, (coordinate, artifact.attrib["name"]))
                self.assertIsNotNone(SHA256.fullmatch(hashes[0].attrib.get("value", "")))

    def test_local_trust_rules_are_exact_and_reject_lookalikes(self) -> None:
        root, namespace = _verification_tree()
        rules = root.findall("v:configuration/v:trusted-artifacts/v:trust", namespace)
        self.assertEqual(
            {(rule.get("group"), rule.get("name")) for rule in rules},
            EXPECTED_LOCAL_TRUST,
        )
        self.assertTrue(all(rule.get("regex") == "true" for rule in rules))
        self.assertTrue(all("remote repository rejects" in rule.get("reason", "") for rule in rules))

        allowed = (
            ("remapped.dev.architectury", "architectury-65f153da"),
            ("remapped.net.fabricmc.fabric-api", "fabric-api-base-65f153da"),
            ("loom", "mappings"),
            ("net.minecraft", "minecraft-merged-bdabb3aae4"),
            ("net.minecraft", "forge-1.20.1-47.4.9-minecraft-merged"),
            ("net.minecraft", "neoforge-21.11.38-beta-minecraft-merged"),
            ("net.minecraft", "neoforge-26.2.0.6-beta-minecraft-merged-deobf"),
            # Rebuild of a target whose overlay ships META-INF/accesstransformer.cfg: Loom writes the
            # project-AT-processed game JAR to the project's loom-cache/minecraftMaven repository.
            ("net.minecraft", "neoforge-26.1.0.19-beta-minecraft-merged-df325f964f"),
            ("net.minecraft", "neoforge-26.1.1.15-beta-minecraft-merged-df325f964f"),
            ("net.minecraftforge." + "a" * 64, "fmlloader"),
            ("net.neoforged.fancymodloader." + "a" * 64, "loader"),
        )
        rejected = (
            ("remapped", "architectury"),
            ("remappedx.dev.architectury", "architectury"),
            ("loom.evil", "mappings"),
            ("loom", "mappings-extra"),
            ("net.minecraft.evil", "minecraft-merged-bdabb3aae4"),
            ("net.minecraft", "minecraft"),
            ("net.minecraft", "forge-1.20.1-47.4.9-minecraft-merged-deobf-extra"),
            ("net.minecraft", "neoforged-26.2.0.6-beta-minecraft-merged-deobf"),
            ("net.minecraft", "neoforge-26.1.1.15-beta-minecraft-merged-df325f964"),
            ("net.minecraft", "neoforge-26.1.1.15-beta-minecraft-merged-df325f964f0"),
            ("net.minecraft", "neoforge-26.1.1.15-beta-minecraft-merged-DF325F964F"),
            ("net.minecraft", "neoforge-26.1.1.15-beta-minecraft-merged-df325f964g"),
            ("net.minecraft", "neoforge-26.1.1.15-beta-minecraft-merged-df325f964f-extra"),
            ("net.minecraft", "neoforge-26.1.1.15-beta-minecraft-merged-deobf-df325f964f"),
            ("net.minecraft", "neoforge-26.1.1.15-beta-minecraft-merged-df325f964f-deobf"),
            ("net.minecraft.evil", "neoforge-26.1.1.15-beta-minecraft-merged-df325f964f"),
            ("net.minecraftforge." + "a" * 63, "fmlloader"),
            ("net.minecraftforge." + "g" * 64, "fmlloader"),
            ("net.minecraftforge." + "a" * 64, "forge"),
            ("net.neoforged.fancymodloader." + "a" * 63, "loader"),
            ("net.neoforged.fancymodloader." + "g" * 64, "loader"),
            ("net.neoforged.fancymodloader." + "a" * 64, "loader-extra"),
        )
        for coordinate in allowed:
            with self.subTest(allowed=coordinate):
                self.assertEqual(sum(_matches_trust(rule, *coordinate) for rule in rules), 1)
        for coordinate in rejected:
            with self.subTest(rejected=coordinate):
                self.assertFalse(any(_matches_trust(rule, *coordinate) for rule in rules))

    def test_every_forge_like_target_trusts_each_loom_merged_output_shape(self) -> None:
        # The release workflow builds each target twice. A clean first build may resolve only the
        # shared merged JAR, while the rebuild sees generated overlay resources and resolves Loom's
        # project-processed JAR, so every shape must be trusted for every real loader version.
        root, namespace = _verification_tree()
        rules = root.findall("v:configuration/v:trusted-artifacts/v:trust", namespace)
        properties = dict(
            line.split("=", 1)
            for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines()
            if "=" in line and not line.lstrip().startswith("#")
        )
        matrix = json.loads((ROOT / "release" / "release-matrix.json").read_text(encoding="utf-8"))
        forge_like = [
            row for row in matrix["artifacts"] if row["loader"] in {"forge", "neoforge"}
        ]
        self.assertTrue(any(row["loader"] == "neoforge" for row in forge_like))
        for row in forge_like:
            key = f"{row['loader']}_version_{row['artifact_version'].replace('.', '_')}"
            loader_version = properties[key].strip()
            base = f"{row['loader']}-{loader_version}-minecraft-merged"
            for name in (base, f"{base}-deobf", f"{base}-df325f964f"):
                with self.subTest(target=row["artifact_node"], name=name):
                    self.assertEqual(
                        sum(_matches_trust(rule, "net.minecraft", name) for rule in rules), 1
                    )
                    self.assertFalse(
                        any(_matches_trust(rule, "net.minecraft.evil", name) for rule in rules)
                    )

    def test_trusted_local_outputs_are_not_recorded_as_portable_hashes(self) -> None:
        root, namespace = _verification_tree()
        rules = root.findall("v:configuration/v:trusted-artifacts/v:trust", namespace)
        for component in root.findall("v:components/v:component", namespace):
            coordinate = (component.attrib["group"], component.attrib["name"])
            with self.subTest(coordinate=coordinate):
                self.assertFalse(any(_matches_trust(rule, *coordinate) for rule in rules))

    def test_active_direct_graph_is_present_in_verification_metadata(self) -> None:
        root, namespace = _verification_tree()
        coordinates = {
            (
                component.attrib["group"],
                component.attrib["name"],
                component.attrib["version"],
            )
            for component in root.findall("v:components/v:component", namespace)
        }
        properties = {}
        for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
            if line and not line.startswith("#") and "=" in line:
                key, value = line.split("=", 1)
                properties[key] = value

        settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
        stonecutter_declaration = re.search(
            r'id\("dev[.]kikugie[.]stonecutter"\) version "([^"]+)"',
            settings,
        )
        self.assertIsNotNone(stonecutter_declaration)
        assert stonecutter_declaration is not None
        stonecutter_version = stonecutter_declaration.group(1)

        expected = {
            ("net.fabricmc", "fabric-loader", properties["fabric_loader_version_1_20_1"]),
            (
                "net.fabricmc.fabric-api",
                "fabric-api",
                properties["fabric_api_version_1_20_1"],
            ),
            ("net.minecraftforge", "forge", properties["forge_version_1_20_1"]),
            (
                "dev.architectury",
                "architectury-fabric",
                properties["architectury_api_version_1_20_1"],
            ),
            (
                "dev.architectury",
                "architectury-forge",
                properties["architectury_api_version_1_20_1"],
            ),
            ("com.twelvemonkeys.imageio", "imageio-webp", "3.15.3"),
            ("org.junit.jupiter", "junit-jupiter", "5.13.4"),
            ("dev.architectury", "architectury-loom", "1.17.480"),
            ("dev.kikugie", "stonecutter", stonecutter_version),
            ("com.gradleup.shadow", "shadow-gradle-plugin", "8.3.11"),
            ("org.gradle.toolchains", "foojay-resolver", "1.0.0"),
        }
        self.assertEqual(expected - coordinates, set())
        self.assertEqual(
            {
                coordinate
                for coordinate in coordinates
                if coordinate[:2]
                in {
                    ("dev.kikugie", "stonecutter"),
                    (
                        "dev.kikugie.stonecutter",
                        "dev.kikugie.stonecutter.gradle.plugin",
                    ),
                }
            },
            {
                ("dev.kikugie", "stonecutter", stonecutter_version),
                (
                    "dev.kikugie.stonecutter",
                    "dev.kikugie.stonecutter.gradle.plugin",
                    stonecutter_version,
                ),
            },
        )

    def test_shadow_marker_pom_trusts_only_the_verified_repository_variants(
        self,
    ) -> None:
        root, namespace = _verification_tree()
        component = root.find(
            "v:components/v:component[@group='com.gradleup.shadow']"
            "[@name='com.gradleup.shadow.gradle.plugin'][@version='8.3.11']",
            namespace,
        )
        self.assertIsNotNone(component)
        assert component is not None
        artifact = component.find(
            "v:artifact[@name='com.gradleup.shadow.gradle.plugin-8.3.11.pom']",
            namespace,
        )
        self.assertIsNotNone(artifact)
        assert artifact is not None
        checksum = artifact.find("v:sha256", namespace)
        self.assertIsNotNone(checksum)
        assert checksum is not None
        self.assertEqual(
            checksum.get("value"),
            "2209a68d4aa73f1c8d2077949cb3b66501b4392554ecc37d6f2c9d559dfcade6",
        )
        self.assertEqual(checksum.get("origin"), "Maven Central marker POM")
        self.assertEqual(
            [entry.get("value") for entry in checksum.findall("v:also-trust", namespace)],
            ["a93dd818af331d766e93a10d8062408c02cd0837ef7b69ab5b126f36360284be"],
        )

    def test_repository_policy_keeps_trusted_namespaces_local_only(self) -> None:
        policy = (ROOT / "gradle" / "repository-policy.gradle.kts").read_text(
            encoding="utf-8"
        )
        for required in (
            'excludeGroupByRegex("remapped\\\\..+")',
            'excludeGroup("loom")',
            'excludeGroup("net.minecraft")',
            'excludeGroupByRegex("net\\\\.minecraftforge\\\\.[0-9a-f]{64}")',
            'excludeGroupByRegex("net\\\\.neoforged\\\\.fancymodloader\\\\.[0-9a-f]{64}")',
            "Unapproved remote dependency repository",
            'repositoryScheme != "https"',
        ):
            with self.subTest(required=required):
                self.assertIn(required, policy)

        for module in ("common", "fabric", "forge", "neoforge"):
            script = (ROOT / module / "build.gradle.kts").read_text(encoding="utf-8")
            self.assertIn(
                'apply(from = rootProject.file("gradle/repository-policy.gradle.kts"))',
                script,
            )

        workflows_and_build_logic = "\n".join(
            path.read_text(encoding="utf-8")
            for pattern in ("*.yml", "*.yaml", "*.gradle", "*.gradle.kts")
            for path in ROOT.rglob(pattern)
            if ".gradle" not in path.parts and "build" not in path.parts
        )
        # Every CI and release Gradle run starts from a tracked script (build_matrix.py and the
        # mod-base candidate), so those launchers are held to the same rules as the workflows.
        launchers = subprocess.run(
            ("git", "ls-files", "-z", "--", "*.py", "*.sh", "*.ps1"),
            cwd=ROOT,
            capture_output=True,
            check=True,
        ).stdout.decode("utf-8")
        automation = "\n".join(
            [workflows_and_build_logic]
            + [
                (ROOT / name).read_text(encoding="utf-8")
                for name in launchers.split("\0")
                if name and "tests" not in Path(name).parts
            ]
        )
        self.assertIsNone(
            re.search(r"--dependency-verification(?:=|\s+)(?:off|lenient)\b", automation)
        )
        # Only the root gradle.properties selects the mode. A launcher that wrote the property
        # into a Gradle user home or GRADLE_OPTS would override it for that run.
        self.assertIsNone(re.search(r"org[.]gradle[.]dependency[.]verification", automation))
        # Checksums and locks are reviewed maintainer changes. Automation that wrote them would
        # accept whatever an upstream repository served on that run.
        self.assertIsNone(
            re.search(
                r"--write-verification-metadata|--write-locks\b"
                r"|(?<![\w-])-M(?:\s+|[\"']\s*,\s*[\"']|=)(?:sha|md5|pgp)",
                automation,
            )
        )

    def test_architectury_transformer_fabric_loader_is_pinned_and_verified(self) -> None:
        """Architectury declares `fabric-loader:+` on its transformer classpath.

        With strict verification a dynamic selector there resolves each new Fabric Loader release
        the day it is published and fails every build on the missing checksum (#1438). The policy
        pins it to one reviewed version, and Gradle itself refuses any dynamic or changing selector
        left in that classpath.
        """
        policy = (ROOT / "gradle" / "repository-policy.gradle.kts").read_text(
            encoding="utf-8"
        )
        declaration = re.search(
            r'^val architecturyTransformerFabricLoaderVersion = "([^"]+)"$',
            policy,
            re.MULTILINE,
        )
        self.assertIsNotNone(declaration)
        assert declaration is not None
        version = declaration.group(1)
        self.assertRegex(version, r"^[0-9]+(?:[.][0-9]+)+$")

        rule = re.search(
            r'configurations[.]matching \{ it[.]name == "architecturyTransformerClasspath" \}'
            r"[.]configureEach \{\n(?P<body>.*?)\n\}\n",
            policy,
            re.DOTALL,
        )
        self.assertIsNotNone(rule)
        assert rule is not None
        body = rule.group("body")
        for required in (
            'requested.group == "net.fabricmc"',
            'requested.name == "fabric-loader"',
            'requested.version == "+"',
            "useVersion(architecturyTransformerFabricLoaderVersion)",
            "failOnDynamicVersions()",
            "failOnChangingVersions()",
        ):
            with self.subTest(required=required):
                self.assertIn(required, body)

        root, namespace = _verification_tree()
        component = root.find(
            "v:components/v:component[@group='net.fabricmc'][@name='fabric-loader']"
            f"[@version='{version}']",
            namespace,
        )
        self.assertIsNotNone(component)
        assert component is not None
        for extension in ("jar", "pom"):
            with self.subTest(artifact=extension):
                checksum = component.find(
                    f"v:artifact[@name='fabric-loader-{version}.{extension}']/v:sha256",
                    namespace,
                )
                self.assertIsNotNone(checksum)
                assert checksum is not None
                self.assertRegex(checksum.get("value", ""), SHA256)

    def test_build_logic_declares_no_dynamic_versions(self) -> None:
        """Every version the build declares is exact; strict verification needs fixed inputs.

        `+` inside a version (Fabric API's `0.92.6+1.20.1`) is build metadata, not a selector;
        only a trailing `+` (Gradle's prefix selector), a `latest.*` selector or a range is dynamic.
        """
        dynamic = re.compile(r"\+$|^latest[.]|^[\[\]\(][^\]\)]*[\]\)\[]$")
        for line in (ROOT / "gradle.properties").read_text(encoding="utf-8").splitlines():
            if line.startswith("#") or "=" not in line:
                continue
            key, value = line.split("=", 1)
            if "version" in key:
                with self.subTest(property=key):
                    self.assertIsNone(dynamic.search(value.strip()), value)

        scripts = subprocess.run(
            ("git", "ls-files", "-z", "--", "*.gradle.kts", "*.gradle"),
            cwd=ROOT,
            capture_output=True,
            check=True,
        ).stdout.decode("utf-8")
        notation = re.compile(
            r'"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:(?:[^"$]*\+|latest[.][a-z]+|[\[\(][^"]*)"'
            r'|version\s*\(?\s*"(?:[^"]*\+|latest[.][a-z]+)"'
        )
        for name in scripts.split("\0"):
            if not name:
                continue
            with self.subTest(script=name):
                self.assertEqual(
                    notation.findall((ROOT / name).read_text(encoding="utf-8")), []
                )

    def test_maven_mirror_has_an_emergency_switch(self) -> None:
        mirror_url = 'maven("https://the-plum-team.github.io/maven-mirror/")'
        guard = 'if (plumMirrorSwitch != "false") {'
        for relative in ("settings.gradle.kts", "gradle/maven-mirror.gradle.kts"):
            with self.subTest(script=relative):
                script = (ROOT / relative).read_text(encoding="utf-8")
                self.assertIn(
                    'val plumMirrorSwitch = providers.gradleProperty("plum.mavenMirror").orNull',
                    script,
                )
                self.assertIn('"plum.mavenMirror must be true or false', script)
                # The mirror is declared exactly once, inside the switch's block.
                self.assertEqual(script.count("the-plum-team.github.io/maven-mirror/"), 1)
                start = script.index(guard)
                self.assertGreater(script.index(mirror_url), start)
                depth = 0
                for position in range(start + len(guard) - 1, len(script)):
                    depth += {"{": 1, "}": -1}.get(script[position], 0)
                    if depth == 0:
                        break
                self.assertLess(script.index(mirror_url), position)
        self.assertIn(
            "-Pplum.mavenMirror=false",
            (ROOT / "DEPENDENCY-SECURITY.md").read_text(encoding="utf-8"),
        )

    def test_only_active_shadow_bundles_are_locked_outside_generated_trees(self) -> None:
        matrix = json.loads((ROOT / "release" / "release-matrix.json").read_text())
        expected_files = {
            f'{artifact["loader"]}-{artifact["artifact_version"]}.lockfile'
            for artifact in matrix["artifacts"]
        }
        lock_root = ROOT / "gradle" / "dependency-locks"
        actual_files = {path.name for path in lock_root.glob("*.lockfile")}
        self.assertEqual(actual_files, expected_files)
        for lock_file in lock_root.glob("*.lockfile"):
            entries = [
                line
                for line in lock_file.read_text(encoding="utf-8").splitlines()
                if line and not line.startswith("#")
            ]
            self.assertEqual(
                entries,
                [
                    "com.twelvemonkeys.common:common-image:3.15.3=shadowBundle",
                    "com.twelvemonkeys.common:common-io:3.15.3=shadowBundle",
                    "com.twelvemonkeys.common:common-lang:3.15.3=shadowBundle",
                    "com.twelvemonkeys.imageio:imageio-core:3.15.3=shadowBundle",
                    "com.twelvemonkeys.imageio:imageio-metadata:3.15.3=shadowBundle",
                    "com.twelvemonkeys.imageio:imageio-webp:3.15.3=shadowBundle",
                    "empty=",
                ],
            )
        self.assertEqual(list(ROOT.glob("**/versions/**/*.lockfile")), [])


if __name__ == "__main__":
    unittest.main()
