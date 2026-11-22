#!/usr/bin/env bash
set -euo pipefail

HOP_HOME=${1:-${HOP_HOME:-}}
if [[ $# -gt 1 || -z "$HOP_HOME" ]]; then
  echo "Usage: $0 [<HOP_HOME>] (defaults to HOP_HOME)" >&2
  exit 1
fi
PLUGIN_DIR="$HOP_HOME/plugins/misc/hop-geometry-type"

mvn -q -DskipTests package

shopt -s nullglob
zip_files=(assemblies/assemblies-hop-geometry-type/target/hop-geometry-type-plugin-*.zip)
if [[ ${#zip_files[@]} -ne 1 ]]; then
  echo "Expected one built Geometry Type ZIP, found ${#zip_files[@]}" >&2
  exit 1
fi
rm -rf "$PLUGIN_DIR"
unzip -q -o "${zip_files[0]}" -d "$HOP_HOME"

echo "Plugin synced to $PLUGIN_DIR"
