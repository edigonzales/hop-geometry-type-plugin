"""Regression checks for version-independent distribution verification."""
import pathlib
import subprocess
import tempfile
import unittest
import zipfile

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "check-distribution.sh"
ROOT = "plugins/misc/hop-geometry-type/"


class DistributionTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.target = pathlib.Path(self.temp.name)

    def archive(self, version="0.2.0-SNAPSHOT", extra=(), omit=()):
        entries = [ROOT + "hop-geometry-type-" + version + ".jar",
                   ROOT + "lib/jts-core-1.20.0.jar"]
        with zipfile.ZipFile(self.target / ("hop-geometry-type-plugin-" + version + ".zip"), "w") as archive:
            for entry in entries + list(extra):
                if entry not in omit:
                    archive.writestr(entry, b"fixture")

    def check(self):
        return subprocess.run(["bash", str(SCRIPT), str(self.target)],
                              text=True, capture_output=True)

    def test_no_zip_has_actionable_error(self):
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("found 0", result.stderr)

    def test_current_and_future_versions(self):
        for version in ("0.2.0-SNAPSHOT", "1.0.0"):
            with self.subTest(version=version):
                self.archive(version)
                result = self.check()
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                next(self.target.glob("*.zip")).unlink()

    def test_multiple_zips_fail(self):
        self.archive()
        self.archive("1.0.0")
        result = self.check()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("found 2", result.stderr)

    def test_forbidden_runtime_jars(self):
        for entry in ("jts-core-1.20.0.jar", "postgresql-42.jar",
                      "lib/postgresql-42.jar", "postgis-jdbc-1.jar", "lib/postgis-jdbc-1.jar"):
            with self.subTest(entry=entry):
                self.archive(extra=[ROOT + entry])
                self.assertNotEqual(self.check().returncode, 0)

    def test_missing_required_runtime_jars(self):
        for entry in ("hop-geometry-type-0.2.0-SNAPSHOT.jar", "lib/jts-core-1.20.0.jar"):
            with self.subTest(entry=entry):
                self.archive(omit=[ROOT + entry])
                self.assertNotEqual(self.check().returncode, 0)


if __name__ == "__main__":
    unittest.main()
