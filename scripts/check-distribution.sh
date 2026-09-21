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

required_runtime=(
  'jts-core-'
  'gt-main-'
  'gt-render-'
  'gt-wms-'
  'gt-wmts-'
  'gt-epsg-hsql-'
  'gt-geotiff-'
  'imagen-core-'
  'imageio-ext-'
  'indriya-'
  'unit-api-'
  'systems-common-'
)
for fragment in "${required_runtime[@]}"; do
  if ! grep -E "plugins/misc/hop-geometry-type/lib/${fragment}[^/]+\\.jar$" "$LAYOUT_FILE" >/dev/null; then
    echo "Shared Geometry runtime is missing a JAR matching ${fragment}" >&2
    exit 1
  fi
done

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

# Verify the SPI inside the shipped plugin JAR, not only in Maven's classes directory.
python3 - "$ZIP" <<'PYTHON'
import io
import sys
import zipfile

provider = "com.atolcd.hop.gis.imagen.GeoToolsRegistryAllowListProvider"
service = "META-INF/services/org.eclipse.imagen.spi.RegistryAllowListProvider"
with zipfile.ZipFile(sys.argv[1]) as bundle:
    candidates = [name for name in bundle.namelist()
                  if name.startswith("plugins/misc/hop-geometry-type/hop-geometry-type-")
                  and name.endswith(".jar") and name.count("/") == 3]
    if len(candidates) != 1:
        raise SystemExit("Expected exactly one Geometry Type plugin JAR")
    with zipfile.ZipFile(io.BytesIO(bundle.read(candidates[0]))) as plugin:
        if provider.replace(".", "/") + ".class" not in plugin.namelist():
            raise SystemExit("Missing GeoTools ImageN allowlist provider class")
        if service not in plugin.namelist():
            raise SystemExit("Missing ImageN RegistryAllowListProvider service file")
        providers = [line.split("#", 1)[0].strip()
                     for line in plugin.read(service).decode("utf-8").splitlines()]
        if provider not in providers:
            raise SystemExit("Missing GeoTools ImageN allowlist service registration")
PYTHON
