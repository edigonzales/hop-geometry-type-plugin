package com.atolcd.hop.gis.geometry.curve;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

class CurveWkbCodecTest {
  private static final String CURVE_POLYGON_WKB =
      "010A00000001000000010800000005000000000000000000000000000000000000000000000000001040"
          + "000000000000000000000000000010400000000000001040000000000000000000000000000010400000"
          + "0000000000000000000000000000";

  @Test
  void readsCurvePolygonWithCircularStringWithoutLosingArcControlPoints() {
    Geometry geometry = new CurveWkbReader().read(HexFormat.of().parseHex(CURVE_POLYGON_WKB));

    assertThat(geometry).isInstanceOf(CurvePolygon.class);
    CurvePolygon polygon = (CurvePolygon) geometry;
    assertThat(polygon.getCurveRings()).hasSize(1);
    assertThat(polygon.getCurveRings().get(0)).isInstanceOf(CircularString.class);

    CircularString ring = (CircularString) polygon.getCurveRings().get(0);
    assertThat(ring.getControlPoints()).hasSize(5);
    assertThat(ring.getControlPoints()[1].x).isEqualTo(4.0);
    assertThat(ring.getControlPoints()[1].y).isEqualTo(0.0);
    assertThat(ring.getControlPoints()[3].x).isEqualTo(0.0);
    assertThat(ring.getControlPoints()[3].y).isEqualTo(4.0);

    // The inherited JTS Polygon remains usable through a linearized coordinate view.
    assertThat(polygon.getExteriorRing().getNumPoints()).isGreaterThan(5);
    assertThat(polygon.getExteriorRing().isClosed()).isTrue();
  }

  @Test
  void roundTripsExactCurveWkbTypesAndControlPoints() {
    CurveWkbReader reader = new CurveWkbReader();
    CurveWkbWriter writer = new CurveWkbWriter();

    Geometry first = reader.read(HexFormat.of().parseHex(CURVE_POLYGON_WKB));
    byte[] encoded = writer.write(first);
    Geometry second = reader.read(encoded);

    assertThat(second).isInstanceOf(CurvePolygon.class);
    CurvePolygon polygon = (CurvePolygon) second;
    assertThat(polygon.getCurveRings().get(0)).isInstanceOf(CircularString.class);

    Coordinate[] expected =
        ((CircularString) ((CurvePolygon) first).getCurveRings().get(0)).getControlPoints();
    Coordinate[] actual = ((CircularString) polygon.getCurveRings().get(0)).getControlPoints();
    assertThat(actual).hasSameSizeAs(expected);
    for (int i = 0; i < expected.length; i++) {
      assertThat(actual[i].x).isEqualTo(expected[i].x);
      assertThat(actual[i].y).isEqualTo(expected[i].y);
    }

    // Top-level type 10 and nested type 8 are preserved, rather than being silently stroked.
    assertThat(encoded[1]).isEqualTo((byte) CurveWkbReader.WKB_CURVEPOLYGON);
    assertThat(encoded[10]).isEqualTo((byte) CurveWkbReader.WKB_CIRCULARSTRING);
  }

  @Test
  void roundTripsCurveSridAsEwkb() {
    CurvePolygon polygon =
        (CurvePolygon) new CurveWkbReader().read(HexFormat.of().parseHex(CURVE_POLYGON_WKB));
    polygon.setSRID(2056);

    byte[] encoded = new CurveWkbWriter().write(polygon);
    Geometry decoded = new CurveWkbReader().read(encoded);

    int rawType =
        ByteBuffer.wrap(encoded, 1, Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).getInt();
    assertThat(rawType & CurveWkbReader.EWKB_SRID).isNotZero();
    assertThat(rawType & CurveWkbReader.EWKB_TYPE_MASK).isEqualTo(CurveWkbReader.WKB_CURVEPOLYGON);
    assertThat(decoded).isInstanceOf(CurvePolygon.class);
    assertThat(decoded.getSRID()).isEqualTo(2056);
  }

  @Test
  void roundTripsEwkbAndSqlMmDimensionsInBothByteOrders() throws Exception {
    for (ByteOrder order : new ByteOrder[] {ByteOrder.LITTLE_ENDIAN, ByteOrder.BIG_ENDIAN})
      for (int dimension = 1; dimension <= 3; dimension++) {
        Coordinate[] points = new Coordinate[3];
        double[][] xy = {{0, 0}, {1, 1}, {2, 0}};
        for (int i = 0; i < 3; i++)
          points[i] =
              dimension == 1
                  ? new Coordinate(xy[i][0], xy[i][1], i + 1)
                  : dimension == 2
                      ? new org.locationtech.jts.geom.CoordinateXYM(xy[i][0], xy[i][1], i + 10)
                      : new org.locationtech.jts.geom.CoordinateXYZM(
                          xy[i][0], xy[i][1], i + 1, i + 10);
        var curve = new CircularString(points, new GeometryFactory());
        String text = new CurveWktWriter().write(curve);
        assertThat(text)
            .startsWith(
                "CIRCULARSTRING " + (dimension == 1 ? "Z" : dimension == 2 ? "M" : "ZM") + " (");
        assertThat(text)
            .contains(dimension == 1 ? "0 0 1" : dimension == 2 ? "0 0 10" : "0 0 1 10");
        byte[] wkb = new CurveWkbWriter(order).write(curve);
        for (boolean iso : new boolean[] {false, true}) {
          if (iso) ByteBuffer.wrap(wkb, 1, 4).order(order).putInt(1000 * dimension + 8);
          var decoded = (CircularString) CurveGeometrySupport.readWkb(wkb);
          for (int i = 0; i < 3; i++) {
            assertThat(Double.valueOf(decoded.getControlPoints()[i].getZ()))
                .isEqualTo(Double.valueOf(points[i].getZ()));
            assertThat(Double.valueOf(decoded.getControlPoints()[i].getM()))
                .isEqualTo(Double.valueOf(points[i].getM()));
          }
        }
      }
  }

  @Test
  void linearizesClosedThreePointCircularStringAsFullCircle() {
    CircularString circle =
        new CircularString(
            new Coordinate[] {new Coordinate(0, 0), new Coordinate(2, 0), new Coordinate(0, 0)},
            new GeometryFactory());

    assertThat(circle.isClosed()).isTrue();
    assertThat(circle.getNumPoints()).isGreaterThan(3);
    assertThat(circle.getEnvelopeInternal().getMinY()).isLessThan(-0.99);
    assertThat(circle.getEnvelopeInternal().getMaxY()).isGreaterThan(0.99);
    Coordinate halfway = circle.getCoordinateN(circle.getNumPoints() / 2);
    assertThat(halfway.x).isEqualTo(2.0);
    assertThat(halfway.y).isEqualTo(0.0);
  }
}
