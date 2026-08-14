package com.atolcd.hop.gis.geometry.postgis;

import static org.assertj.core.api.Assertions.assertThat;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CurveGeometrySupport;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

class PostgisGeometrySupportTest {
  private final GeometryFactory factory = new GeometryFactory();

  @Test
  void shouldRoundTripCurveThroughPostgisHexEwkbWithoutLinearizing() throws Exception {
    CircularString curve =
        new CircularString(
            new Coordinate[] {
              new Coordinate(0, 0), new Coordinate(2, 2), new Coordinate(4, 0)
            },
            factory);
    curve.setSRID(2056);

    String hexEwkb = HexFormat.of().formatHex(CurveGeometrySupport.writeWkb(curve));
    Geometry decoded = PostgisGeometrySupport.read(hexEwkb);

    assertThat(decoded).isInstanceOf(CircularString.class);
    assertThat(decoded.getSRID()).isEqualTo(2056);
    assertThat(((CircularString) decoded).getControlPoints())
        .extracting(c -> c.x, c -> c.y)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(0.0, 0.0),
            org.assertj.core.groups.Tuple.tuple(2.0, 2.0),
            org.assertj.core.groups.Tuple.tuple(4.0, 0.0));
  }

  @Test
  void shouldWriteCurveAsEwkt() {
    CircularString curve =
        new CircularString(
            new Coordinate[] {
              new Coordinate(0, 0), new Coordinate(2, 2), new Coordinate(4, 0)
            },
            factory);
    curve.setSRID(2056);

    assertThat(PostgisGeometrySupport.write(curve))
        .isEqualTo("SRID=2056;CIRCULARSTRING (0 0, 2 2, 4 0)");
  }

  @Test
  void shouldAcceptStandardEwktFallback() throws Exception {
    Geometry geometry = PostgisGeometrySupport.read("SRID=2056;POINT(1 2)");

    assertThat(geometry.getGeometryType()).isEqualTo("Point");
    assertThat(geometry.getSRID()).isEqualTo(2056);
    assertThat(geometry.toText()).isEqualTo("POINT (1 2)");
  }
}
