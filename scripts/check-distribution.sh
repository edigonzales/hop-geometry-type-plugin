#!/usr/bin/env bash
set -euo pipefail
TARGET_DIR="${1:-assemblies/assemblies-hop-geometry-type/target}"
shopt -s nullglob
zip_files=("$TARGET_DIR"/hop-geometry-type-plugin-*.zip)
if [[ ${#zip_files[@]} -ne 1 ]]; then
  echo "Expected exactly one geometry type plugin ZIP in $TARGET_DIR, found ${#zip_files[@]}" >&2
  exit 1
fi
ZIP="${zip_files[0]}"
echo "Checking $ZIP"
LAYOUT_FILE="$(mktemp)"
trap 'rm -f "$LAYOUT_FILE"' EXIT
unzip -l "$ZIP" > "$LAYOUT_FILE"

grep -E 'plugins/misc/hop-geometry-type/hop-geometry-type-[^/]+\.jar$' "$LAYOUT_FILE"
grep -E 'plugins/misc/hop-geometry-type/lib/jts-core-[^/]+\.jar$' "$LAYOUT_FILE"

if grep -E 'plugins/misc/hop-geometry-type/jts-core-[^/]+\.jar$' "$LAYOUT_FILE"; then
  echo "jts-core must be packaged under hop-geometry-type/lib, not in the plugin root" >&2
  exit 1
fi

if grep -E 'plugins/misc/hop-geometry-type/(lib/)?postgresql-[^/]+\.jar$' "$LAYOUT_FILE"; then
  echo "PostgreSQL JDBC must be provided by Hop's PostgreSQL database plugin, not sogeo-geometry" >&2
  exit 1
fi

if grep -E 'plugins/misc/hop-geometry-type/(lib/)?postgis-jdbc-[^/]+\.jar$' "$LAYOUT_FILE"; then
  echo "postgis-jdbc must not be packaged in the shared geometry runtime" >&2
  exit 1
fi
