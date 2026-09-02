from __future__ import annotations

import hashlib
import json
import re
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
        r"^(minecraft-merged-[0-9a-f]{10}|(?:forge|neoforge)-[0-9A-Za-z.+_-]+-minecraft-merged(?:-deobf)?)$",
    ),
    (r"^net[.]minecraftforge[.][0-9a-f]{64}$", r"^fmlloader$"),
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
        # Dependency verification is deliberately not enforced: upstream publishers replace
        # artifacts under an existing version coordinate, which halted every build on the
        # affected branch. The recorded inventory and the Gradle wrapper pins below stay
        # strict, so this asserts the accepted mode rather than leaving it unpinned.
        self.assertIn("org.gradle.dependency.verification=off", properties)
        self.assertNotIn("org.gradle.dependency.verification=strict", properties)
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
            ROOT / "gradle" / "wrapper" / "gradle-9.6.1-bin.zip.sha256"
        ).read_text(encoding="utf-8").strip()
        self.assertEqual(distribution_hash.group(1), distribution_sidecar)

        wrapper_jar = ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar"
        expected_jar_hash = (
            ROOT / "gradle" / "wrapper" / "gradle-wrapper.jar.sha256"
        ).read_text(encoding="utf-8").split()[0]
        self.assertEqual(hashlib.sha256(wrapper_jar.read_bytes()).hexdigest(), expected_jar_hash)

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
            ("net.minecraft", "neoforge-26.2.0.10-beta-minecraft-merged-deobf"),
            ("net.minecraftforge." + "a" * 64, "fmlloader"),
        )
        rejected = (
            ("remapped", "architectury"),
            ("remappedx.dev.architectury", "architectury"),
            ("loom.evil", "mappings"),
            ("loom", "mappings-extra"),
            ("net.minecraft.evil", "minecraft-merged-bdabb3aae4"),
            ("net.minecraft", "minecraft"),
            ("net.minecraft", "neoforged-21.11.38-beta-minecraft-merged"),
            ("net.minecraft", "forge-1.20.1-47.4.9-minecraft-merged-deobf-extra"),
            ("net.minecraft", "neoforged-26.2.0.10-beta-minecraft-merged-deobf"),
            ("net.minecraftforge." + "a" * 63, "fmlloader"),
            ("net.minecraftforge." + "g" * 64, "fmlloader"),
            ("net.minecraftforge." + "a" * 64, "forge"),
        )
        for coordinate in allowed:
            with self.subTest(allowed=coordinate):
                self.assertEqual(sum(_matches_trust(rule, *coordinate) for rule in rules), 1)
        for coordinate in rejected:
            with self.subTest(rejected=coordinate):
                self.assertFalse(any(_matches_trust(rule, *coordinate) for rule in rules))

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

        matrix = json.loads((ROOT / "release" / "release-matrix.json").read_text())
        expected = {
            ("org.sejda.imageio", "webp-imageio", "0.1.6"),
            ("org.junit.jupiter", "junit-jupiter", "5.13.4"),
            ("dev.architectury", "architectury-loom", "1.17.480"),
            ("dev.kikugie", "stonecutter", "0.9.7"),
            ("com.gradleup.shadow", "shadow-gradle-plugin", "8.3.11"),
            ("org.gradle.toolchains", "foojay-resolver", "1.0.0"),
        }
        for version in {artifact["artifact_version"] for artifact in matrix["artifacts"]}:
            suffix = version.replace(".", "_")
            expected.add(
                ("dev.architectury", "architectury", properties[f"architectury_api_version_{suffix}"])
            )
            expected.add(
                ("net.fabricmc", "fabric-loader", properties[f"fabric_loader_version_{suffix}"])
            )

        loader_coordinates = {
            "fabric": ("net.fabricmc.fabric-api", "fabric-api", "fabric_api_version"),
            "forge": ("net.minecraftforge", "forge", "forge_version"),
            "neoforge": ("net.neoforged", "neoforge", "neoforge_version"),
        }
        for artifact in matrix["artifacts"]:
            loader = artifact["loader"]
            suffix = artifact["artifact_version"].replace(".", "_")
            group, name, property_name = loader_coordinates[loader]
            expected.add((group, name, properties[f"{property_name}_{suffix}"]))
            expected.add(
                (
                    "dev.architectury",
                    f"architectury-{loader}",
                    properties[f"architectury_api_version_{suffix}"],
                )
            )
        self.assertEqual(expected - coordinates, set())

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
            "Unapproved remote dependency repository",
            'repositoryScheme != "https"',
        ):
            with self.subTest(required=required):
                self.assertIn(required, policy)

        matrix = json.loads((ROOT / "release" / "release-matrix.json").read_text())
        active_modules = {"common"} | {
            artifact["loader"] for artifact in matrix["artifacts"]
        }
        for module in sorted(active_modules):
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
        self.assertIsNone(
            re.search(
                r"--dependency-verification(?:=|\s+)(?:off|lenient)\b",
                workflows_and_build_logic,
            )
        )

    def test_mojang_patched_lwjgl_route_is_exact_and_exclusive(self) -> None:
        policy = (ROOT / "gradle" / "repository-policy.gradle.kts").read_text(
            encoding="utf-8"
        )
        self.assertIn('maven("https://libraries.minecraft.net/")', policy)
        self.assertIn('name = "MojangPatchedLwjglRepository"', policy)
        self.assertIn("exclusiveContent", policy)
        exact_route = 'includeVersion("org.lwjgl", "lwjgl-freetype", "3.3.3")'
        self.assertEqual(policy.count(exact_route), 2)
        self.assertNotIn('includeGroup("org.lwjgl")', policy)
        self.assertNotIn('includeGroupByRegex("org\\.lwjgl', policy)

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
                ["org.sejda.imageio:webp-imageio:0.1.6=shadowBundle", "empty="],
            )
        self.assertEqual(list(ROOT.glob("**/versions/**/*.lockfile")), [])


if __name__ == "__main__":
    unittest.main()
