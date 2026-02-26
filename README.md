# hop-geometry-type

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
<version>0.1.0-SNAPSHOT</version>
```

## Compatibility
- Java: **17**
- Apache Hop: **2.17** (Plugin ist dafür kompatibel)
- CI build: Linux (ubuntu-latest)

## Repository layout
```text
.
├── hop-geometry-type/                          # core plugin jar with ValueMetaGeometry
├── assemblies/assemblies-hop-geometry-type/   # builds/installable Hop plugin ZIP artifact (hop-geometry-type-plugin)
├── scripts/dev-sync-hop-plugin.sh             # fast local sync into HOP_HOME
└── .github/workflows/release.yml              # build+test and publish workflow
```

## Build
### Full build (jar + tests + plugin zip)
```bash
mvn clean verify
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

## Use in your own plugin project
If you only need the Java API/type at compile/runtime:

```xml
<dependency>
  <groupId>ch.so.agi</groupId>
  <artifactId>hop-geometry-type</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

For Hop runtime installation, install the ZIP into your Hop home.

## Fast local development / live testing
For rapid iteration against a local Hop installation:

1. Set `HOP_HOME` (or pass path explicitly).
2. Run:
   ```bash
   ./scripts/dev-sync-hop-plugin.sh /path/to/hop
   ```
3. Restart Hop GUI / server and test your pipeline.

What the script does:
- builds the project
- recreates plugin target directory in `${HOP_HOME}`
- unzips latest plugin artifact directly into Hop

This provides a short feedback loop when refining geometry-related plugin code.

## Publishing to jars.sogeo.services
Deployment target is configured via `distributionManagement`:

- Releases: `https://jars.sogeo.services/repository/maven-releases/`
- Snapshots: `https://jars.sogeo.services/repository/maven-snapshots/`

GitHub Actions workflow:
- always runs build/tests on PRs and pushes (Linux / ubuntu-latest)
- publishes only on **push to `main`** (i.e. after merge)

Required repository secrets:
- `MAVEN_USERNAME`
- `MAVEN_PASSWORD`

No GPG signing is required.

Published artifacts are now intentionally separated by role:
- `hop-geometry-type-parent` (parent POM only)
- `hop-geometry-type` (core JAR)
- `hop-geometry-type-plugin` (plugin ZIP distribution)

Maven deploy target selection is version-based:
- `*-SNAPSHOT` versions are deployed to `sogeo-snapshots`
- non-SNAPSHOT versions are deployed to `sogeo-releases`

## Notes on extraction provenance
Implementation originates from:
- https://github.com/atolcd/hop-gis-plugins

This project isolates the geometry type so downstream plugins can depend on it without pulling in all GIS transforms.
