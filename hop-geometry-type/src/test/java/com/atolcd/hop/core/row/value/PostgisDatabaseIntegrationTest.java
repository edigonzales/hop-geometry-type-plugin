package com.atolcd.hop.core.row.value;

import static org.assertj.core.api.Assertions.assertThat;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CompoundCurve;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.apache.hop.core.HopEnvironment;
import org.apache.hop.core.database.Database;
import org.apache.hop.core.database.DatabaseMeta;
import org.apache.hop.core.row.IRowMeta;
import org.apache.hop.core.row.RowMeta;
import org.apache.hop.core.row.value.ValueMetaInteger;
import org.apache.hop.core.variables.Variables;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

@EnabledIfEnvironmentVariable(named = "POSTGIS_TEST_ENABLED", matches = "true")
class PostgisDatabaseIntegrationTest {
  private static Database database;

  @BeforeAll
  static void setUp() throws Exception {
    HopEnvironment.init();

    DatabaseMeta databaseMeta =
        new DatabaseMeta(
            "postgis-test",
            "POSTGRESQL",
            "Native",
            env("POSTGIS_TEST_HOST", "localhost"),
            env("POSTGIS_TEST_DATABASE", "hop"),
            env("POSTGIS_TEST_PORT", "5432"),
            env("POSTGIS_TEST_USER", "hop"),
            env("POSTGIS_TEST_PASSWORD", "hop"));

    database = new Database(null, new Variables(), databaseMeta);
    database.connect();
    database.execStatement("CREATE EXTENSION IF NOT EXISTS postgis");
    database.execStatement("DROP TABLE IF EXISTS hop_curve_output");
    database.execStatement("DROP TABLE IF EXISTS hop_curve_input");
    database.execStatement("CREATE TABLE hop_curve_input (id integer PRIMARY KEY, geom geometry)");
    database.execStatement("CREATE TABLE hop_curve_output (id integer PRIMARY KEY, geom geometry)");
    database.execStatement(
        "INSERT INTO hop_curve_input VALUES "
            + "(1, ST_GeomFromText('POINT(2600000 1200000)', 2056)), "
            + "(2, ST_GeomFromText('CIRCULARSTRING(0 0,2 2,4 0)', 2056)), "
            + "(3, ST_GeomFromText('COMPOUNDCURVE((0 0,1 0),CIRCULARSTRING(1 0,2 1,3 0))', 2056)), "
            + "(4, ST_GeomFromText('CURVEPOLYGON(CIRCULARSTRING(0 0,4 0,4 4,0 4,0 0))', 2056))");
  }

  @AfterAll
  static void tearDown() {
    if (database != null) {
      database.disconnect();
    }
    HopEnvironment.reset();
  }

  @Test
  void shouldDetectReadWriteAndRoundTripPostgisCurvesThroughHopDatabase() throws Exception {
    List<Object[]> inputRows = readGeometryRows("hop_curve_input");
    assertGeometryClassesAndSrid(inputRows);

    IRowMeta outputMeta = new RowMeta();
    outputMeta.addValueMeta(new ValueMetaInteger("id"));
    outputMeta.addValueMeta(new ValueMetaGeometry("geom"));

    database.prepareInsert(outputMeta, "public", "hop_curve_output");
    try {
      for (Object[] row : inputRows) {
        database.setValuesInsert(outputMeta, row);
        database.insertRow();
      }
    } finally {
      database.closeInsert();
    }

    try (ResultSet rs =
        database.openQuery(
            "SELECT id, GeometryType(geom), ST_SRID(geom), ST_AsText(geom) "
                + "FROM hop_curve_output ORDER BY id")) {
      assertPostgisTypeRow(rs, 1, "POINT", "POINT(2600000 1200000)");
      assertPostgisTypeRow(rs, 2, "CIRCULARSTRING", "CIRCULARSTRING(0 0,2 2,4 0)");
      assertPostgisTypeRow(rs, 3, "COMPOUNDCURVE", "COMPOUNDCURVE");
      assertPostgisTypeRow(rs, 4, "CURVEPOLYGON", "CURVEPOLYGON");
    }

    List<Object[]> outputRows = readGeometryRows("hop_curve_output");
    assertGeometryClassesAndSrid(outputRows);

    CircularString circularString = (CircularString) outputRows.get(1)[1];
    assertThat(circularString.getControlPoints())
        .extracting(c -> c.x, c -> c.y)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(0.0, 0.0),
            org.assertj.core.groups.Tuple.tuple(2.0, 2.0),
            org.assertj.core.groups.Tuple.tuple(4.0, 0.0));
  }

  private static List<Object[]> readGeometryRows(String table) throws Exception {
    ResultSet rs = database.openQuery("SELECT id, geom FROM " + table + " ORDER BY id");
    try {
      IRowMeta rowMeta = database.getReturnRowMeta();
      assertThat(rowMeta).isNotNull();
      assertThat(rowMeta.getValueMeta(1)).isInstanceOf(ValueMetaGeometry.class);
      assertThat(rowMeta.getValueMeta(1).getType()).isEqualTo(ValueMetaGeometry.TYPE_GEOMETRY);

      List<Object[]> rows = new ArrayList<>();
      Object[] row;
      while ((row = database.getRow(rs, false)) != null) {
        rows.add(row);
      }
      return rows;
    } finally {
      database.closeQuery(rs);
    }
  }

  private static void assertGeometryClassesAndSrid(List<Object[]> rows) {
    assertThat(rows).hasSize(4);
    assertThat(rows.get(0)[1]).isInstanceOf(Point.class);
    assertThat(rows.get(1)[1]).isInstanceOf(CircularString.class);
    assertThat(rows.get(2)[1]).isInstanceOf(CompoundCurve.class);
    assertThat(rows.get(3)[1]).isInstanceOf(CurvePolygon.class);
    for (Object[] row : rows) {
      assertThat(((Geometry) row[1]).getSRID()).isEqualTo(2056);
    }
  }

  private static void assertPostgisTypeRow(
      ResultSet rs, int expectedId, String expectedType, String expectedText) throws Exception {
    assertThat(rs.next()).isTrue();
    assertThat(rs.getInt(1)).isEqualTo(expectedId);
    assertThat(rs.getString(2)).isEqualTo(expectedType);
    assertThat(rs.getInt(3)).isEqualTo(2056);
    if (expectedText.endsWith("CURVE")) {
      assertThat(rs.getString(4)).startsWith(expectedText);
    } else if (expectedText.equals("CURVEPOLYGON")) {
      assertThat(rs.getString(4)).startsWith(expectedText);
    } else {
      assertThat(rs.getString(4).replace(" ", "")).isEqualTo(expectedText.replace(" ", ""));
    }
  }

  private static String env(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }
}
