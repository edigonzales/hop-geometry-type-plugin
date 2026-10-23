"""Validate actual Java codec output against pinned, offline OGC JSON-FG 1.0 schemas."""

import hashlib
import json
from pathlib import Path
import unittest

from jsonschema import Draft202012Validator
from referencing import Registry, Resource

ROOT = Path(__file__).resolve().parents[2]
SCHEMAS = ROOT / "hop-geometry-type/src/test/resources/jsonfg-1.0"
EMITTED = ROOT / "hop-geometry-type/target/jsonfg-emitted.json"


def offline_registry():
    manifest = json.loads((SCHEMAS / "manifest.json").read_text())
    resources = []
    for uri, entry in manifest.items():
        data = (SCHEMAS / entry["file"]).read_bytes()
        if hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise AssertionError("Modified vendored schema: " + uri)
        resources.append((uri, Resource.from_contents(json.loads(data))))
    return Registry().with_resources(resources)


class JsonFgSchemaTest(unittest.TestCase):
    def setUp(self):
        registry = offline_registry()
        schema = registry.get_or_retrieve(
            "https://schemas.opengis.net/json-fg/geometry-object.json").value.contents
        self.validator = Draft202012Validator(schema, registry=registry)

    def test_emitted_geometry_documents_match_official_schema(self):
        self.assertTrue(EMITTED.is_file(), "Run Maven tests before Python schema validation")
        documents = json.loads(EMITTED.read_text())
        self.assertGreaterEqual(len(documents), 18)
        for document in documents:
            with self.subTest(type=document["type"]):
                self.validator.validate(document)

    def test_schema_rejects_unsupported_arc_and_collection_shapes(self):
        invalid = [
            {"type": "CircularString", "coordinates": [[n, n % 2] for n in range(13)]},
            {"type": "GeometryCollection", "geometries": [
                {"type": "GeometryCollection", "geometries": []}]},
            {"type": "CompoundCurve", "geometries": [
                {"type": "LineString", "coordinates": [[0, 0], [1, 1]],
                 "coordRefSys": "EPSG:2056"}]},
        ]
        for document in invalid:
            with self.subTest(document=document):
                self.assertFalse(self.validator.is_valid(document))

    def test_unregistered_schema_is_not_retrieved_from_network(self):
        registry = offline_registry()
        with self.assertRaises(Exception):
            registry.get_or_retrieve("https://example.invalid/unknown-schema.json")


if __name__ == "__main__":
    unittest.main()
