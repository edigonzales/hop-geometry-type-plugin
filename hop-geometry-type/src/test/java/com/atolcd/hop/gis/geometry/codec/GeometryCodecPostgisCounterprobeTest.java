package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

@EnabledIfEnvironmentVariable(named = "POSTGIS_TEST_ENABLED", matches = "true")
class GeometryCodecPostgisCounterprobeTest {
  private static final String POINT_ZM_EWKB =
      "00E0000001000008083FF0000000000000400000000000000040080000000000004010000000000000";

  private static Connection connection;
  private static final GeometryCodec CODEC = new GeometryCodec(GeometryReadLimits.defaults());

  @BeforeAll
  static void connect() throws Exception {
    connection =
        DriverManager.getConnection(
            "jdbc:postgresql://"
                + env("POSTGIS_TEST_HOST", "localhost")
                + ":"
                + env("POSTGIS_TEST_PORT", "5432")
                + "/"
                + env("POSTGIS_TEST_DATABASE", "hop"),
            env("POSTGIS_TEST_USER", "hop"),
            env("POSTGIS_TEST_PASSWORD", "hop"));
  }

  @AfterAll
  static void disconnect() throws Exception {
    if (connection != null) {
      connection.close();
    }
  }

  @Test
  void fixedEwkbPointZmIsAcceptedByPostgisAndRoundTripsThroughTheCodec() throws Exception {
    try (PreparedStatement statement =
            connection.prepareStatement(
                "SELECT ST_GeometryType(g), ST_SRID(g), ST_NDims(g), ST_AsEWKB(g), ST_AsText(g) "
                    + "FROM (SELECT ST_GeomFromEWKB(?) AS g) AS fixture");
        ) {
      statement.setBytes(1, GeometryHexCodec.decode(POINT_ZM_EWKB));
      try (ResultSet result = statement.executeQuery()) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("ST_Point");
        assertThat(result.getInt(2)).isEqualTo(2056);
        assertThat(result.getInt(3)).isEqualTo(4);
        assertThat(result.getString(5)).contains("POINT ZM").contains("1 2 3 4");

        Geometry resolved = CODEC.readBinary(result.getBytes(4));
        assertThat(resolved).isInstanceOf(Point.class);
        assertThat(resolved.getSRID()).isEqualTo(2056);
        PointCoordinates point = PointCoordinateSupport.extract(resolved);
        assertThat(point.layout()).isEqualTo(CoordinateLayout.XYZM);
        assertThat(point.x()).isEqualTo(1.0);
        assertThat(point.y()).isEqualTo(2.0);
        assertThat(point.z()).isEqualTo(3.0);
        assertThat(point.m()).isEqualTo(4.0);
      }
    }
  }

  private static String env(String name, String defaultValue) {
    String value = System.getenv(name);
    return value == null || value.isBlank() ? defaultValue : value;
  }
}
