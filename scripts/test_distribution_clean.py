import tempfile
import unittest
from pathlib import Path
from zipfile import ZipFile

from verify_distribution_clean import CANARY, GENERATED_FILES, check, seed


class StaleOutputTest(unittest.TestCase):
    def test_seed_uses_generated_files_only_and_never_overwrites(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            seed(root)
            self.assertTrue(all((root / name).is_file() for name in GENERATED_FILES))
            with self.assertRaises(FileExistsError):
                seed(root)

    def test_remaining_generated_output_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            seed(root)
            with self.assertRaisesRegex(ValueError, "STALE_GENERATED_FILE"):
                check(root)

    def test_remaining_artifact_entry_is_rejected_and_clean_artifacts_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "dist").mkdir()
            for suffix in ("jar", "vsix"):
                with ZipFile(root / "dist" / f"cheapreview-0.1.0.{suffix}", "w") as archive:
                    archive.writestr("normal.txt", "public fixture")
            check(root)
            with ZipFile(root / "dist/cheapreview-0.1.0.jar", "a") as archive:
                archive.writestr(f"BOOT-INF/classes/{CANARY}.class", "public fixture")
            with self.assertRaisesRegex(ValueError, "STALE_ARTIFACT_ENTRY"):
                check(root)


if __name__ == "__main__":
    unittest.main()
