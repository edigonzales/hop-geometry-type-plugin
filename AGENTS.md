# Repository instructions

## CI and tests

Before changing pipelines or test setup, read the
[shared CI contract](https://github.com/edigonzales/hop-plugin-ci/blob/main/docs/ci-contract.md).
The documentation follows `main`; use the interfaces at this repo's actual
workflow/helper revisions and preserve existing pins and `ci-ref` values.

Run the commands below from this repository root in Bash, using Python 3, Maven
and JDK 21 (`JAVA_HOME` and `PATH` pointing to that JDK). Compatibility jobs also
use JDK 25. For headless Linux SWT tests, run Maven under `xvfb-run -a`.
Set `HOP_CI_DIR` to an absolute checkout of `hop-plugin-ci` at the helper revision
used by this repo's workflow, then prepare the same Maven repositories as CI:

```bash
CI_TEST_TMP="$(mktemp -d)"
export MAVEN_SETTINGS="$CI_TEST_TMP/maven-settings.xml"
python3 "$HOP_CI_DIR/scripts/write_maven_settings.py" --output "$MAVEN_SETTINGS"
```

### Custom canonical build

[.github/workflows/verify.yml](.github/workflows/verify.yml) owns the build instead
of calling reusable plugin verify. Its canonical Ubuntu/JDK 21 job requires a
running PostGIS service (`postgis/postgis:17-3.5`) with the following test database
and credentials. For a local equivalent, provision that service first and use:

```bash
export POSTGIS_TEST_ENABLED=true
export POSTGIS_TEST_HOST=localhost POSTGIS_TEST_PORT=5432
export POSTGIS_TEST_DATABASE=hop POSTGIS_TEST_USER=hop POSTGIS_TEST_PASSWORD=hop
mvn -s "$MAVEN_SETTINGS" -U -B -ntp clean verify
python3 -m unittest discover -s scripts/tests -v
bash scripts/check-distribution.sh
```

The distribution check requires `unzip` and exactly one ZIP in
`assemblies/assemblies-hop-geometry-type/target`. It verifies the Geometry/JTS
layout and excludes database drivers that Hop supplies.

For the separate Java/OS compatibility tests, use a shell without the canonical
PostGIS opt-in, as in CI:

```bash
unset POSTGIS_TEST_ENABLED
mvn -s "$MAVEN_SETTINGS" -U -B -ntp test
```

The custom canonical job creates the legacy ZIP manifest and also carries the
already-built shared Geometry JAR, module POM and parent POM for downstream Maven
consumers. Its pinned publication workflow deploys these files without rebuilding.
No separate installed-Hop E2E job exists in this verify workflow; retain its
PostGIS and package checks rather than describing a nonexistent test stage.
