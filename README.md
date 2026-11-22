# hop-geometry-type-plugin

Standalone Apache Hop plugin that contributes a **Geometry value type** (`ValueMetaGeometry`) for usage in custom Hop plugins/transforms without requiring the full `hop-gis-plugins` project.

## Scope
This repository extracts and packages the geometry type implementation from `atolcd/hop-gis-plugins` with the **same Java package names** in code:

- `com.atolcd.hop.core.row.value.GeometryInterface`
- `com.atolcd.hop.core.row.value.ValueMetaGeometry`
- supporting utility: `com.atolcd.hop.gis.utils.GeometryUtils`

The Maven coordinates are intentionally under `ch.so.agi` (Plugin-Version getrennt von Hop-Version):

```xml
<groupId>ch.so.agi</groupId>
<artifactId>hop-geometry-type-parent</artifactId>
<version>0.2.0-SNAPSHOT</version>
```

## Compatibility
- Java: **21** (compatibility tests also run on 25)
- Apache Hop: **2.19.0**
- CI build: Linux (ubuntu-latest)

## Geometry and PostGIS integration

`ValueMetaGeometry` participates in Apache Hop's normal JDBC value-type discovery. A native PostGIS `geometry` result column (`Types.OTHER`, type name `geometry`) is therefore exposed by standard Hop database transforms as a Hop **Geometry** field; no dedicated PostGIS reader transform is required.

Typical read path:

```text
Table Input -> PostgreSQL JDBC -> ValueMetaGeometry -> Hop Geometry
```

Select the native geometry column, for example:

```sql
SELECT id, geom
FROM my_schema.my_table;
```

Avoid `ST_AsText(geom)` or `ST_AsEWKB(geom)` when the downstream pipeline should receive a Geometry value; those expressions intentionally change the JDBC result type to string/binary.

For PostgreSQL/PostGIS, the shared geometry runtime decodes the native hex EWKB representation itself and binds writes as EWKT with `Types.OTHER`. This preserves supported SQL/MM true-curve types without routing them through the ordinary JTS WKT writer:

- `CIRCULARSTRING`
- `COMPOUNDCURVE`
- `CURVEPOLYGON`
- `MULTICURVE`
- `MULTISURFACE`

The PostgreSQL JDBC driver belongs to Hop's PostgreSQL database plugin and is **not** packaged in `hop-geometry-type`. Likewise, `postgis-jdbc` is not part of the shared `sogeo-geometry` runtime.

This means standard database transforms can be used in both directions:

```text
PostGIS -> Table Input -> Geometry-aware transforms -> Table Output -> PostGIS
```

CI verifies the actual Hop `Database` read/write path against a real PostGIS service, including geometry type detection, SRID 2056, and true-curve read/write/read round trips.

## GeoJSON and JSON-FG

The shared `GeometryCodec` supports explicit Geometry conversion for RFC 7946,
Legacy GeoJSON 2008 and JSON-FG 1.0.0, including supported true curves and M values.
It preserves CRS/measure context separately from the Geometry and never reprojects
or discards dimensions implicitly. See the [GeoJSON API and format guide](docs/geojson.md)
for examples, axis order, height requirements, loss options and format limits.
Feature and FeatureCollection processing belongs to Vector/Raster; the
Geoprocessing Geometry Converter exposes the shared Geometry codec in pipelines.

## Repository layout
```text
.
├── hop-geometry-type/                          # core plugin jar with ValueMetaGeometry
├── assemblies/assemblies-hop-geometry-type/   # builds/installable Hop plugin ZIP artifact (hop-geometry-type-plugin)
├── scripts/dev-sync-hop-plugin.sh             # fast local sync into HOP_HOME
└── .github/workflows/                         # verification and snapshot publication
```

## Build
### Full build (jar + tests + plugin zip)
```bash
mvn clean verify
```

The PostGIS integration test is enabled in CI with `POSTGIS_TEST_ENABLED=true`. For local execution, start a PostGIS instance and provide the optional connection variables:

```text
POSTGIS_TEST_ENABLED=true
POSTGIS_TEST_HOST=localhost
POSTGIS_TEST_PORT=5432
POSTGIS_TEST_DATABASE=hop
POSTGIS_TEST_USER=hop
POSTGIS_TEST_PASSWORD=hop
```

### Build without tests
```bash
mvn -DskipTests package
```

## Produced artifacts
After `mvn package`:

- Core jar: `hop-geometry-type/target/hop-geometry-type-<version>.jar`
- Plugin zip (with runtime dependencies):  
  `assemblies/assemblies-hop-geometry-type/target/hop-geometry-type-plugin-<version>.zip`

The ZIP contains all runtime dependencies under:

```text
plugins/misc/hop-geometry-type/
```

The installable ZIP is also the single owner of the shared `sogeo-geometry` runtime:
GeoTools 35.1 (including WMTS, WMS, rendering, EPSG and GeoTIFF), Eclipse Imagen 0.9.2,
ImageIO-Ext, Indriya/UOM and their support libraries. Geometry Inspector, Raster Type and
Vector/Raster use this classloader and must not package their own copies. The PostgreSQL and
PostGIS JDBC drivers remain owned by Hop's database plugin and are intentionally excluded.

## Use in your own plugin project
Use the Maven coordinate for the Java API and Geometry type at compile time:

```xml
<dependency>
  <groupId>ch.so.agi</groupId>
  <artifactId>hop-geometry-type</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
```

Install the ZIP into your Hop home to provide the shared runtime and its libraries.
Standalone Maven consumers that exercise the runtime must supply any required
`provided` libraries, such as Jackson and GeoTools for the GeoJSON codec.

When updating, stop Hop, replace the complete `plugins/misc/hop-geometry-type` directory
with the contents of the new Geometry Type ZIP, then restart Hop GUI/server completely.
The plugin supplies an ImageN service provider that explicitly allows the four GeoTools
palette registry classes (`ColorReductionDescriptor`, `ColorInversionDescriptor`,
`ColorReductionCRIF`, `ColorInversionCRIF`). ImageN caches this allowlist on first use;
restarting a pipeline alone does not reload it. A `HopEnvironmentAfterInit` extension
initializes ImageN with the shared plugin context classloader so its service provider is
visible even when later GUI/pipeline threads use a different context loader. No additional
JVM options are needed.

## Fast local development / live testing
For rapid iteration against a local Hop installation:

1. Run the sync script with the path to your Hop installation:
   ```bash
   ./scripts/dev-sync-hop-plugin.sh /path/to/hop
   ```
   With `HOP_HOME` set in the environment, omit the path argument.
2. Restart Hop GUI / server and test your pipeline.

What the script does:
- builds the plugin ZIP without running tests
- replaces the installed Geometry Type plugin with the contents of the built ZIP

Use the full build and the verification commands in [AGENTS.md](AGENTS.md) for acceptance.

## Publishing to jars.interlis.guru
GitHub Actions verifies PRs and pushes to `main`. The canonical build uses
Ubuntu and JDK 21 with PostGIS. The compatibility matrix runs tests on Linux,
macOS and Windows with JDK 21 and 25.

The [snapshot workflow](.github/workflows/publish-snapshot.yml) publishes to
`https://jars.interlis.guru/snapshots/` after verification on a push to `main`
or a manual run on `main`.

Required repository secrets:
- `MAVEN_USERNAME`
- `MAVEN_PASSWORD`

No GPG signing is required.

Published artifacts are now intentionally separated by role:
- `hop-geometry-type-parent` (parent POM only)
- `hop-geometry-type` (core JAR)
- `hop-geometry-type-plugin` (plugin ZIP distribution)

## Notes on extraction provenance
Implementation originates from:
- https://github.com/atolcd/hop-gis-plugins

This project isolates the geometry type so downstream plugins can depend on it without pulling in all GIS transforms.

## Z/M preservation (0.2.0-SNAPSHOT)

Linear XY, XYZ, XYM and XYZM geometries retain coordinate sequences and SRIDs during cloning and Hop internal WKB serialization, including missing ordinates and empty geometries. The outer Hop stream framing is unchanged and old XY WKB remains readable. WKT output includes M where present. Circular WKB and WKT output now preserve XYZ/XYM/XYZM as well. Individual database adapters still define their own supported dimensions.

Install this Geometry plugin build together with the updated Inspector, Raster Type and
Vector/Raster plugins. Keep a single Geometry Type installation in the shared `sogeo-geometry`
classloader group; it supplies JTS as well as the common GeoTools/Imagen runtime.

## Strict shared geometry codec (0.2.0-SNAPSHOT)

The common API under `com.atolcd.hop.gis.geometry.codec` provides explicit, loss-aware text and
binary conversion:

- `GeometryCodec.readText/readBinary/readHex` and `writeText/writeBinary/writeHex`;
- WKT/EWKT, ISO/SQL-MM WKB and EWKB, both byte orders and explicit SRID options;
- XY, XYZ, XYM and XYZM based on declared coordinate sequences, including all-NaN Z/M ordinates;
- SQL/MM `CIRCULARSTRING`, `COMPOUNDCURVE`, `CURVEPOLYGON`, `MULTICURVE` and `MULTISURFACE`,
  including curves nested in ordinary geometry collections;
- stable `GeometryCodecException` error codes, configurable read limits and exact copies.

`ValueMetaGeometry.convertData(...)` now accepts WKB/EWKB binary input and recognises explicit or
plausible hex strings in addition to the existing text path. `ValueMetaGeometry.getBinary(...)`
implements the normal Binary target hook (`new ValueMetaBinary(...).convertData(geometryMeta,
geometry)`), producing plain EWKB with big-endian byte order and no Hop stream framing.

Legacy compatibility is deliberate: the historical `GeometryUtils.getGeometryFromEWKT(...)` path
still maps `EMPTY` to `NULL` for existing Select Values pipelines, while `GeometryCodec` preserves
empty geometry objects. Standard `getString()` output and the internal Hop stream framing are
unchanged.

## Explicit circular-curve linearization

`CurveGeometrySupport.linearize(geometry, maxError)` recursively creates linear geometries from
exact curve controls. `maxError` is a positive finite maximum XY chord deviation in source units.
Subdivision preserves the original middle control point and interpolates Z/M on either side.
Reversing a nondegenerate arc produces the same coordinates in reverse order. Curve `copy()` and
`reverse()` preserve exact curve objects; full-circle reversal uses two unambiguous half arcs.

Cross-row coverage coordination belongs to the Geoprocessing plugin's `coverage_linearize`
operation. A row-local linearization cannot ensure shared chords when neighbours describe the
same circle using different control-point subdivisions.

The circular WKB codecs preserve XY/XYZ/XYM/XYZM using EWKB flags or SQL/MM type offsets.
`GeometryCodec` and the extended `CurveWktReader` also read curved WKT with declared layout
markers, `EMPTY` rules and full input-consumption checks. The legacy Select Values text path uses
the same parser through `GeometryUtils`, but keeps its historical `EMPTY → NULL` normalisation.
See [docs/implementation/geometry-converter-stage-b.md](docs/implementation/geometry-converter-stage-b.md)
for the implementation record.
