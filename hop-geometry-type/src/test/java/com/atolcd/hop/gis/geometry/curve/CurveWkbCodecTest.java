package com.atolcd.hop.gis.geometry.curve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    int rawType = ByteBuffer.wrap(encoded, 1, Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN).getInt();
    assertThat(rawType & CurveWkbReader.EWKB_SRID).isNotZero();
    assertThat(rawType & CurveWkbReader.EWKB_TYPE_MASK)
        .isEqualTo(CurveWkbReader.WKB_CURVEPOLYGON);
    assertThat(decoded).isInstanceOf(CurvePolygon.class);
    assertThat(decoded.getSRID()).isEqualTo(2056);
  }

  @Test
  void rejectsEwkbZCurveInsteadOfSilentlyDroppingZ() {
    byte[] wkb = HexFormat.of().parseHex(CURVE_POLYGON_WKB);
    ByteBuffer.wrap(wkb, 1, Integer.BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(CurveWkbReader.WKB_CURVEPOLYGON | CurveWkbReader.EWKB_Z);

    assertThatThrownBy(() -> new CurveWkbReader().read(wkb))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Z/M");
  }

  @Test
  void rejectsSqlMmZCurveInsteadOfDelegatingToLinearJts() {
    byte[] wkb = HexFormat.of().parseHex(CURVE_POLYGON_WKB);
    ByteBuffer.wrap(wkb, 1, Integer.BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(1000 + CurveWkbReader.WKB_CURVEPOLYGON);

    assertThatThrownBy(() -> CurveGeometrySupport.readWkb(wkb))
        .isInstanceOf(org.locationtech.jts.io.ParseException.class)
        .hasMessageContaining("Z/M");
  }

  @Test
  void rejectsWritingCurveCoordinatesWithZ() {
    CircularString circularString =
        new CircularString(
            new Coordinate[] {
              new Coordinate(0, 0, 1), new Coordinate(1, 1, 1), new Coordinate(2, 0, 1)
            },
            new GeometryFactory());

    assertThatThrownBy(() -> new CurveWkbWriter().write(circularString))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Z/M");
  }

  @Test
  void linearizesClosedThreePointCircularStringAsFullCircle() {
    CircularString circle =
        new CircularString(
            new Coordinate[] {
              new Coordinate(0, 0), new Coordinate(2, 0), new Coordinate(0, 0)
            },
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
