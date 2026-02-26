#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 <HOP_HOME>"
  exit 1
fi

HOP_HOME=$1
PLUGIN_DIR="$HOP_HOME/plugins/misc/hop-geometry-type"

mvn -q -DskipTests package

rm -rf "$PLUGIN_DIR"
mkdir -p "$PLUGIN_DIR"
cp assemblies/assemblies-hop-geometry-type/target/hop-geometry-type-plugin-*.zip /tmp/hop-geometry-type-plugin.zip
unzip -q -o /tmp/hop-geometry-type-plugin.zip -d "$HOP_HOME"

echo "Plugin synced to $PLUGIN_DIR"
