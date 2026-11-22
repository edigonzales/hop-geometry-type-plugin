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


class JsonFgSchemaTest(unittest.TestCase):
    def test_emitted_geometry_documents_match_official_schema(self):
        manifest = json.loads((SCHEMAS / "manifest.json").read_text())
        resources = []
        for uri, entry in manifest.items():
            data = (SCHEMAS / entry["file"]).read_bytes()
            if hashlib.sha256(data).hexdigest() != entry["sha256"]:
                raise AssertionError("Modified vendored schema: " + uri)
            resources.append((uri, Resource.from_contents(json.loads(data))))
        registry = Registry().with_resources(resources)
        schema = registry.get_or_retrieve(
            "https://schemas.opengis.net/json-fg/geometry-object.json").value.contents
        validator = Draft202012Validator(schema, registry=registry)

        self.assertTrue(EMITTED.is_file(), "Run Maven tests before Python schema validation")
        documents = json.loads(EMITTED.read_text())
        self.assertGreaterEqual(len(documents), 18)
        for document in documents:
            with self.subTest(type=document["type"]):
                validator.validate(document)


if __name__ == "__main__":
    unittest.main()
