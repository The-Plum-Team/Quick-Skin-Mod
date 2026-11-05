from __future__ import annotations

import stat
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / "scripts" / "ci"))

import bounded_zip  # noqa: E402


class BoundedZipTest(unittest.TestCase):
    def _archive(self, root: Path, entries: dict[str, bytes]) -> Path:
        archive = root / "evidence.zip"
        with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as output:
            for name, payload in entries.items():
                output.writestr(name, payload)
        return archive

    def test_extracts_regular_entries_into_a_fresh_destination(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = self._archive(root, {"logs/failure.txt": b"bounded failure\n"})
            destination = root / "extracted"

            summary = bounded_zip.extract_bounded_zip(archive, destination)

            self.assertEqual(
                (destination / "logs" / "failure.txt").read_bytes(),
                b"bounded failure\n",
            )
            self.assertEqual(summary, {"entries": 1, "files": 1, "bytes": 16})

    def test_accepts_an_explicit_directory_after_its_files(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = self._archive(
                root,
                {"logs/failure.txt": b"failure\n", "logs/": b""},
            )

            summary = bounded_zip.extract_bounded_zip(archive, root / "extracted")

            self.assertEqual(
                (root / "extracted" / "logs" / "failure.txt").read_bytes(),
                b"failure\n",
            )
            self.assertEqual(summary, {"entries": 2, "files": 1, "bytes": 8})

    def test_rejects_traversal_absolute_backslash_and_case_collisions(self) -> None:
        cases = (
            {"../escape": b"x"},
            {"/absolute": b"x"},
            {"bad\\path": b"x"},
            {"A.txt": b"x", "a.txt": b"y"},
            {"A/first.txt": b"x", "a/second.txt": b"y"},
            {"parent": b"x", "parent/child": b"y"},
        )
        for index, entries in enumerate(cases):
            with self.subTest(entries=entries), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                archive = self._archive(root, entries)
                destination = root / f"out-{index}"
                with self.assertRaises(bounded_zip.ArchiveError):
                    bounded_zip.extract_bounded_zip(archive, destination)
                self.assertFalse(destination.exists())

    def test_rejects_symlink_and_special_file_entries(self) -> None:
        for mode in (stat.S_IFLNK | 0o777, stat.S_IFIFO | 0o600):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                archive = root / "evidence.zip"
                info = zipfile.ZipInfo("unsafe")
                info.create_system = 3
                info.external_attr = mode << 16
                with zipfile.ZipFile(archive, "w") as output:
                    output.writestr(info, b"target")
                with self.assertRaisesRegex(
                    bounded_zip.ArchiveError, "link|special"
                ):
                    bounded_zip.extract_bounded_zip(archive, root / "out")

    def test_rejects_archive_entry_and_expansion_limits(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = self._archive(root, {"large.txt": b"a" * 64})
            limits = bounded_zip.ExtractionLimits(
                archive_bytes=1024,
                entries=8,
                total_bytes=32,
                file_bytes=128,
                compression_ratio=200,
            )
            with self.assertRaisesRegex(bounded_zip.ArchiveError, "total byte"):
                bounded_zip.extract_bounded_zip(archive, root / "out", limits)

    def test_corrupt_deflate_data_is_an_archive_error_and_leaves_no_tree(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            payload = bytes(range(256)) * 64
            archive = self._archive(root, {"first.txt": b"intact\n", "second.bin": payload})
            with zipfile.ZipFile(archive) as source:
                info = source.getinfo("second.bin")
                header = info.header_offset
            data = bytearray(archive.read_bytes())
            name_length = int.from_bytes(data[header + 26 : header + 28], "little")
            extra_length = int.from_bytes(data[header + 28 : header + 30], "little")
            start = header + 30 + name_length + extra_length
            # 0xff starts a deflate block of the reserved type 3, which zlib rejects.
            data[start : start + 8] = b"\xff" * 8
            archive.write_bytes(bytes(data))
            destination = root / "extracted"

            with self.assertRaises(bounded_zip.ArchiveError):
                bounded_zip.extract_bounded_zip(archive, destination)

            self.assertFalse(destination.exists())
            self.assertEqual(
                sorted(path.name for path in root.iterdir()), ["evidence.zip"]
            )

    def test_rejects_existing_destination_and_archive_symlink(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = self._archive(root, {"value.txt": b"value"})
            destination = root / "out"
            destination.mkdir()
            with self.assertRaisesRegex(bounded_zip.ArchiveError, "must not already"):
                bounded_zip.extract_bounded_zip(archive, destination)

            linked = root / "linked.zip"
            linked.symlink_to(archive)
            with self.assertRaisesRegex(bounded_zip.ArchiveError, "regular file"):
                bounded_zip.extract_bounded_zip(linked, root / "other")


if __name__ == "__main__":
    unittest.main()
