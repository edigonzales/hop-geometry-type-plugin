package com.atolcd.hop.gis.geometry.curve;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

class MultiCurveSurfaceWkbTest {
  private final GeometryFactory factory = new GeometryFactory();

  @Test
  void roundTripsMultiCurveWithLinearAndCircularMembers() throws Exception {
    LineString line =
        factory.createLineString(new Coordinate[] {new Coordinate(10, 0), new Coordinate(12, 2)});
    CircularString arc =
        new CircularString(
            new Coordinate[] {
              new Coordinate(0, 0), new Coordinate(2, 2), new Coordinate(4, 0)
            },
            factory);
    MultiCurve multiCurve = new MultiCurve(List.of(line, arc), factory);
    multiCurve.setSRID(2056);

    byte[] encoded = CurveGeometrySupport.writeWkb(multiCurve);
    Geometry decoded = CurveGeometrySupport.readWkb(encoded);

    assertThat(baseType(encoded)).isEqualTo(CurveWkbReader.WKB_MULTICURVE);
    assertThat(decoded).isInstanceOf(MultiCurve.class);
    MultiCurve actual = (MultiCurve) decoded;
    assertThat(actual.getSRID()).isEqualTo(2056);
    assertThat(actual.getCurves()).hasSize(2);
    assertThat(actual.getCurves().get(0).getClass()).isEqualTo(LineString.class);
    assertThat(actual.getCurves().get(1)).isInstanceOf(CircularString.class);
    assertThat(((CircularString) actual.getCurves().get(1)).getControlPoints())
        .extracting(c -> c.x, c -> c.y)
        .containsExactly(tuple(0.0, 0.0), tuple(2.0, 2.0), tuple(4.0, 0.0));
  }

  @Test
  void roundTripsMultiSurfaceWithLinearAndCurvedMembers() throws Exception {
    Polygon polygon =
        factory.createPolygon(
            new Coordinate[] {
              new Coordinate(10, 10),
              new Coordinate(14, 10),
              new Coordinate(14, 14),
              new Coordinate(10, 14),
              new Coordinate(10, 10)
            });
    CircularString ring =
        new CircularString(
            new Coordinate[] {
              new Coordinate(0, 0),
              new Coordinate(4, 0),
              new Coordinate(4, 4),
              new Coordinate(0, 4),
              new Coordinate(0, 0)
            },
            factory);
    CurvePolygon curvePolygon = new CurvePolygon(List.of(ring), factory);
    MultiSurface multiSurface = new MultiSurface(List.of(polygon, curvePolygon), factory);
    multiSurface.setSRID(2056);

    byte[] encoded = CurveGeometrySupport.writeWkb(multiSurface);
    Geometry decoded = CurveGeometrySupport.readWkb(encoded);

    assertThat(baseType(encoded)).isEqualTo(CurveWkbReader.WKB_MULTISURFACE);
    assertThat(decoded).isInstanceOf(MultiSurface.class);
    MultiSurface actual = (MultiSurface) decoded;
    assertThat(actual.getSRID()).isEqualTo(2056);
    assertThat(actual.getSurfaces()).hasSize(2);
    assertThat(actual.getSurfaces().get(0).getClass()).isEqualTo(Polygon.class);
    assertThat(actual.getSurfaces().get(1)).isInstanceOf(CurvePolygon.class);
    CurvePolygon actualCurvePolygon = (CurvePolygon) actual.getSurfaces().get(1);
    assertThat(actualCurvePolygon.getCurveRings().get(0)).isInstanceOf(CircularString.class);
    assertThat(((CircularString) actualCurvePolygon.getCurveRings().get(0)).getControlPoints())
        .hasSize(5);
  }

  @Test
  void curveAwareCopyPreservesNestedCurveMembers() {
    CircularString arc =
        new CircularString(
            new Coordinate[] {
              new Coordinate(0, 0), new Coordinate(2, 2), new Coordinate(4, 0)
            },
            factory);
    MultiCurve original = new MultiCurve(List.of(arc), factory);

    Geometry copied = CurveGeometrySupport.copy(original);

    assertThat(copied).isInstanceOf(MultiCurve.class);
    assertThat(((MultiCurve) copied).getCurves().get(0)).isInstanceOf(CircularString.class);
  }

  private static int baseType(byte[] wkb) {
    ByteOrder order = (wkb[0] & 0xff) == 1 ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    int rawType = ByteBuffer.wrap(wkb, 1, Integer.BYTES).order(order).getInt();
    return rawType & CurveWkbReader.EWKB_TYPE_MASK;
  }

  private static org.assertj.core.groups.Tuple tuple(Object... values) {
    return org.assertj.core.groups.Tuple.tuple(values);
  }
}
