"""Regression checks for version-independent distribution verification."""
import io
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

    def archive(self, version="0.2.0-SNAPSHOT", extra=(), omit=(), omit_spi=()):
        entries = [ROOT + "hop-geometry-type-" + version + ".jar"]
        entries.extend(
            ROOT + "lib/" + fragment + "fixture.jar"
            for fragment in (
                "jts-core-", "gt-main-", "gt-render-", "gt-wms-", "gt-wmts-",
                "gt-epsg-hsql-", "gt-geotiff-", "imagen-core-", "imageio-ext-",
                "indriya-", "unit-api-", "systems-common-",
            )
        )
        with zipfile.ZipFile(self.target / ("hop-geometry-type-plugin-" + version + ".zip"), "w") as archive:
            for entry in entries + list(extra):
                if entry not in omit:
                    content = b"fixture"
                    if entry == entries[0]:
                        jar = io.BytesIO()
                        provider = "com.atolcd.hop.gis.imagen.GeoToolsRegistryAllowListProvider"
                        with zipfile.ZipFile(jar, "w") as plugin:
                            if "class" not in omit_spi:
                                plugin.writestr(provider.replace(".", "/") + ".class", b"fixture")
                            if "service" not in omit_spi:
                                plugin.writestr(
                                    "META-INF/services/org.eclipse.imagen.spi.RegistryAllowListProvider",
                                    "" if "registration" in omit_spi else provider + "\n")
                        content = jar.getvalue()
                    archive.writestr(entry, content)

    def check(self):
        return subprocess.run(["bash", str(SCRIPT), str(self.target)],
                              text=True, capture_output=True)

    def test_missing_imagen_provider_fails(self):
        for missing in ("class", "service", "registration"):
            with self.subTest(missing=missing):
                self.archive(omit_spi=(missing,))
                result = self.check()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn("Missing", result.stderr)

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
        for entry in ("hop-geometry-type-0.2.0-SNAPSHOT.jar", "lib/jts-core-fixture.jar",
                      "lib/gt-main-fixture.jar", "lib/imagen-core-fixture.jar"):
            with self.subTest(entry=entry):
                self.archive(omit=[ROOT + entry])
                self.assertNotEqual(self.check().returncode, 0)


if __name__ == "__main__":
    unittest.main()
