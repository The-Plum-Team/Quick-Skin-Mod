"""Exercise the shipped alpha fixture against the real skin UVs without launching Minecraft."""
from __future__ import annotations

import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
HARNESS = ROOT / "common/src/e2e"


class SkinTransparencyFixtureJavaTest(unittest.TestCase):
    def test_both_camera_faces_are_translucent_and_hands_stay_opaque(self):
        suffix = ".exe" if os.name == "nt" else ""
        jdk = os.environ.get("JAVA_HOME")
        javac = str(Path(jdk) / "bin" / ("javac" + suffix)) if jdk else "javac"
        java = str(Path(jdk) / "bin" / ("java" + suffix)) if jdk else "java"
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            runner = root / "FixtureCanary.java"
            runner.write_text('''import com.quickskin.mod.e2e.SkinTransparencyFixture;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public final class FixtureCanary {
    static final boolean[][] edited = new boolean[64][64];
    static void alpha(BufferedImage image, int x, int y, int expected) {
        int actual = image.getRGB(x, y) >>> 24;
        if (actual != expected) throw new AssertionError(x + "," + y + ": alpha " + actual + " != " + expected);
        edited[y][x] = true;
    }
    static void arm(BufferedImage image, int u, int v, int overlayU, int overlayV) {
        // Classic geometry has four 4x12 side faces and two 4x4 caps. This skin's
        // brown hand occupies the last row of each side plus the bottom cap.
        for (int face = 0; face < 4; face++) {
            for (int row = 0; row < 12; row++) {
                for (int column = 0; column < 4; column++) {
                    alpha(image, u + face * 4 + column, v + 4 + row, row == 11 ? 255 : 128);
                }
            }
        }
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                alpha(image, u + 4 + column, v + row, 128); // shoulder
                alpha(image, u + 8 + column, v + row, 255); // palm
            }
        }
        for (int row = 0; row < 16; row++) {
            for (int column = 0; column < 16; column++) alpha(image, overlayU + column, overlayV + row, 0);
        }
    }
    public static void main(String[] args) throws Exception {
        BufferedImage image = ImageIO.read(new File(args[0]));
        int[] before = image.getRGB(0, 0, 64, 64, null, 0, 64);
        SkinTransparencyFixture.apply(image);
        arm(image, 40, 16, 40, 32);
        arm(image, 32, 48, 48, 48);
        for (int y = 23; y < 29; y++) for (int x = 34; x < 38; x++) alpha(image, x, y, 0);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                int original = before[y * 64 + x];
                int actual = image.getRGB(x, y);
                if ((original & 0xFFFFFF) != (actual & 0xFFFFFF)) throw new AssertionError("RGB changed");
                if (!edited[y][x] && original != actual) throw new AssertionError("unrelated skin pixel changed");
            }
        }
        try {
            SkinTransparencyFixture.apply(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB));
            throw new AssertionError("legacy UVs accepted as classic");
        } catch (IllegalArgumentException expected) {}
        System.out.println("transparency fixture canaries passed");
    }
}
''', encoding="utf-8")
            compiled = subprocess.run([
                javac, "--release", "17", "-d", str(root / "classes"), str(runner),
                str(HARNESS / "java/com/quickskin/mod/e2e/SkinTransparencyFixture.java"),
            ], capture_output=True, text=True, timeout=60)
            self.assertEqual(0, compiled.returncode, compiled.stdout + compiled.stderr)
            result = subprocess.run([
                java, "-Djava.awt.headless=true", "-cp", str(root / "classes"), "FixtureCanary",
                str(HARNESS / "resources/qs_e2e_test_skin.png"),
            ], capture_output=True, text=True, timeout=30)
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            self.assertIn("transparency fixture canaries passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
