package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;

class GeometryLayoutSupportTest {
  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());
  private final GeometryFactory factory = new GeometryFactory();

  @Test
  void readsDeclaredLayoutsFromSequences() throws Exception {
    assertThat(GeometryLayoutSupport.layoutOf(sequence(2, 0))).isEqualTo(CoordinateLayout.XY);
    assertThat(GeometryLayoutSupport.layoutOf(sequence(3, 0))).isEqualTo(CoordinateLayout.XYZ);
    assertThat(GeometryLayoutSupport.layoutOf(sequence(3, 1))).isEqualTo(CoordinateLayout.XYM);
    assertThat(GeometryLayoutSupport.layoutOf(sequence(4, 1))).isEqualTo(CoordinateLayout.XYZM);

    Point point = (Point) codec.readText("POINT Z (1 2 NaN)");
    assertThat(GeometryLayoutSupport.primitiveLayout(point)).isEqualTo(CoordinateLayout.XYZ);
    assertThat(point.getCoordinateSequence().hasZ()).isTrue();
    assertThat(point.getCoordinateSequence().getZ(0)).isNaN();
  }

  @Test
  void aggregateLayoutIsTheUnionForHeterogeneousCollections() throws Exception {
    Geometry collection =
        codec.readText("GEOMETRYCOLLECTION (POINT (1 2), POINT Z (3 4 5), POINT M (6 7 8))");
    assertThat(GeometryLayoutSupport.aggregateLayout(collection)).isEqualTo(CoordinateLayout.XYZM);
    Geometry collectionZm =
        codec.readText("GEOMETRYCOLLECTION (POINT Z (1 2 3), POINT M (4 5 6))");
    assertThat(GeometryLayoutSupport.aggregateLayout(collectionZm))
        .isEqualTo(CoordinateLayout.XYZM);
  }

  @Test
  void primitiveLayoutTraversesPolygonsAndCurves() throws Exception {
    Geometry polygon = codec.readText("POLYGON M ((0 0 1, 1 0 2, 1 1 3, 0 0 1))");
    assertThat(GeometryLayoutSupport.primitiveLayout(polygon)).isEqualTo(CoordinateLayout.XYM);

    CurvePolygon curvePolygon =
        (CurvePolygon)
            codec.readText(
                "CURVEPOLYGON ZM (CIRCULARSTRING (0 0 1 2, 4 0 2 3, 4 4 3 4, 0 4 4 5, 0 0 1 2))");
    assertThat(GeometryLayoutSupport.primitiveLayout(curvePolygon))
        .isEqualTo(CoordinateLayout.XYZM);

    CircularString curve =
        (CircularString) codec.readText("CIRCULARSTRING Z (1 0 1, 0 1 2, -1 0 3)");
    assertThat(GeometryLayoutSupport.primitiveLayout(curve)).isEqualTo(CoordinateLayout.XYZ);
  }

  @Test
  void validatesFiniteXYAndAllowsNanZm() throws Exception {
    Geometry valid = codec.readText("POINT ZM (1 2 NaN 9)");
    GeometryLayoutSupport.validateRepresentable(valid);

    Point nonFinite = (Point) codec.readText("POINT (1 2)");
    nonFinite.getCoordinateSequence().setOrdinate(0, 0, Double.NaN);
    assertCode(
        () -> GeometryLayoutSupport.validateRepresentable(nonFinite),
        GeometryCodecErrorCode.GC_NONFINITE_COORDINATE);

    Point infiniteZ = (Point) codec.readText("POINT Z (1 2 3)");
    infiniteZ.getCoordinateSequence().setOrdinate(0, 2, Double.POSITIVE_INFINITY);
    assertCode(
        () -> GeometryLayoutSupport.validateRepresentable(infiniteZ),
        GeometryCodecErrorCode.GC_NONFINITE_COORDINATE);
  }

  @Test
  void emptyPrimitivesKeepTheirLayoutAndEmptyCollectionsStayXy() throws Exception {
    Point point = (Point) codec.readText("POINT Z EMPTY");
    assertThat(point.isEmpty()).isTrue();
    assertThat(GeometryLayoutSupport.primitiveLayout(point)).isEqualTo(CoordinateLayout.XYZ);

    LineString line = (LineString) codec.readText("LINESTRING M EMPTY");
    assertThat(line.isEmpty()).isTrue();
    assertThat(GeometryLayoutSupport.primitiveLayout(line)).isEqualTo(CoordinateLayout.XYM);

    Geometry emptyCollection = codec.readText("GEOMETRYCOLLECTION EMPTY");
    assertThat(GeometryLayoutSupport.aggregateLayout(emptyCollection))
        .isEqualTo(CoordinateLayout.XY);
  }

  @Test
  void rejectsLimitViolations() throws Exception {
    Geometry point = codec.readText("POINT (1 2)");
    GeometryReadLimits zero = new GeometryReadLimits(64, 0, 10, 10);
    assertCode(
        () -> GeometryLayoutSupport.validateRepresentable(point, zero),
        GeometryCodecErrorCode.GC_LIMIT_EXCEEDED);
  }

  @Test
  void unsupportedForeignGeometryClassIsRejected() {
    Geometry foreign =
        new LineString(
            factory.getCoordinateSequenceFactory().create(new Coordinate[] {new Coordinate(0, 0), new Coordinate(1, 1)}),
            factory) {};
    assertThat(GeometryLayoutSupport.isSupportedGeometry(foreign)).isFalse();
    assertCode(
        () -> GeometryLayoutSupport.validateRepresentable(foreign),
        GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY);
  }

  private CoordinateSequence sequence(int dimension, int measures) {
    return PackedCoordinateSequenceFactory.DOUBLE_FACTORY.create(2, dimension, measures);
  }

  private static void assertCode(ThrowingCallable call, GeometryCodecErrorCode code) {
    assertThatThrownBy(call)
        .isInstanceOf(GeometryCodecException.class)
        .satisfies(
            error ->
                assertThat(((GeometryCodecException) error).code())
                    .as(error.getMessage())
                    .isEqualTo(code));
  }
}
