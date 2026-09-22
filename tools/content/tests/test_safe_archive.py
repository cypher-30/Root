import tarfile
import tempfile
import unittest
import zipfile
from pathlib import Path

from content_pipeline.safe_archive import (
    safe_extract_zip, safe_extract_tar, UnsafeArchiveMember, ArchiveBudgetExceeded,
)


class SafeExtractZipTests(unittest.TestCase):
    def test_extracts_normal_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            archive = tmp / "a.zip"
            with zipfile.ZipFile(archive, "w") as zf:
                zf.writestr("hello.txt", "hi")
                zf.writestr("sub/dir/world.txt", "world")
            dest = tmp / "out"
            extracted = safe_extract_zip(archive, dest, max_total_bytes=10_000)
            self.assertEqual(len(extracted), 2)
            self.assertTrue((dest / "hello.txt").exists())
            self.assertTrue((dest / "sub" / "dir" / "world.txt").exists())

    def test_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            archive = tmp / "evil.zip"
            with zipfile.ZipFile(archive, "w") as zf:
                zf.writestr("../../escape.txt", "pwned")
            dest = tmp / "out"
            with self.assertRaises(UnsafeArchiveMember):
                safe_extract_zip(archive, dest, max_total_bytes=10_000)

    def test_rejects_over_budget(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            archive = tmp / "big.zip"
            with zipfile.ZipFile(archive, "w") as zf:
                zf.writestr("big.txt", "x" * 1000)
            dest = tmp / "out"
            with self.assertRaises(ArchiveBudgetExceeded):
                safe_extract_zip(archive, dest, max_total_bytes=10)


class SafeExtractTarTests(unittest.TestCase):
    def test_extracts_normal_files(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            src_file = tmp / "hello.txt"
            src_file.write_text("hi")
            archive = tmp / "a.tar"
            with tarfile.open(archive, "w") as tf:
                tf.add(src_file, arcname="hello.txt")
            dest = tmp / "out"
            extracted = safe_extract_tar(archive, dest, max_total_bytes=10_000)
            self.assertEqual(len(extracted), 1)
            self.assertTrue((dest / "hello.txt").exists())

    def test_rejects_symlink_members(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            archive = tmp / "sym.tar"
            with tarfile.open(archive, "w") as tf:
                info = tarfile.TarInfo(name="link")
                info.type = tarfile.SYMTYPE
                info.linkname = "/etc/passwd"
                tf.addfile(info)
            dest = tmp / "out"
            with self.assertRaises(UnsafeArchiveMember):
                safe_extract_tar(archive, dest, max_total_bytes=10_000)

    def test_rejects_path_traversal(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            src_file = tmp / "evil.txt"
            src_file.write_text("pwned")
            archive = tmp / "evil.tar"
            with tarfile.open(archive, "w") as tf:
                tf.add(src_file, arcname="../escape.txt")
            dest = tmp / "out"
            with self.assertRaises(UnsafeArchiveMember):
                safe_extract_tar(archive, dest, max_total_bytes=10_000)


if __name__ == "__main__":
    unittest.main()
