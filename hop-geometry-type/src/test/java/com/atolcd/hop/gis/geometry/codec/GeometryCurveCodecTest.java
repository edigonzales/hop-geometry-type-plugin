package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CompoundCurve;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import com.atolcd.hop.gis.geometry.curve.MultiCurve;
import com.atolcd.hop.gis.geometry.curve.MultiSurface;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.Point;

class GeometryCurveCodecTest {
  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());

  @Test
  void allCurveTypesRoundTripThroughTextAndBinaryWithAllLayouts() throws Exception {
    for (String text :
        new String[] {
          "CIRCULARSTRING Z (0 0 1, 1 1 2, 2 0 3)",
          "CIRCULARSTRING M (0 0 10, 1 1 20, 2 0 30)",
          "CIRCULARSTRING ZM (0 0 1 10, 1 1 2 20, 2 0 3 30)",
          "COMPOUNDCURVE Z ((0 0 1, 1 0 2), CIRCULARSTRING (1 0 2, 2 1 3, 3 0 4))",
          "CURVEPOLYGON Z (CIRCULARSTRING (0 0 1, 4 0 2, 4 4 3, 0 4 4, 0 0 1))",
          "MULTICURVE Z ((0 0 1, 5 5 2), CIRCULARSTRING (10 10 3, 12 12 4, 14 10 5))",
          "MULTISURFACE Z (((0 0 1, 0 2 2, 2 2 3, 0 0 1)), CURVEPOLYGON (CIRCULARSTRING (10 0 1, 12 2 2, 14 0 3, 12 -2 4, 10 0 1)))",
          "GEOMETRYCOLLECTION Z (POINT (1 2 3), CIRCULARSTRING (1 0 1, 0 1 2, -1 0 3), CURVEPOLYGON (CIRCULARSTRING (0 0 1, 4 0 2, 4 4 3, 0 4 4, 0 0 1)))"
        }) {
      Geometry geometry = codec.readText(text);
      Geometry fromWkt = codec.readText(codec.writeText(geometry, WktWriteOptions.wkt()));
      GeometryAssertions.assertGeometryPreserved(geometry, fromWkt);

      byte[] wkb = codec.writeBinary(geometry, WkbWriteOptions.standardFieldBinary());
      Geometry fromWkb = codec.readBinary(wkb);
      GeometryAssertions.assertGeometryPreserved(geometry, fromWkb);
    }
  }

  @Test
  void curveControlsAreExactAndLayoutIsExplicit() throws Exception {
    CircularString curve =
        (CircularString) codec.readText("CIRCULARSTRING ZM (1 2 NaN 9, 3 4 5 10, 5 6 7 11)");
    var sequence = curve.getControlPointSequence();
    assertThat(sequence.getDimension()).isEqualTo(4);
    assertThat(sequence.getMeasures()).isEqualTo(1);
    assertThat(sequence.getX(0)).isEqualTo(1);
    assertThat(sequence.getY(0)).isEqualTo(2);
    assertThat(sequence.getZ(0)).isNaN();
    assertThat(sequence.getM(0)).isEqualTo(9);
    assertThat(sequence.getZ(1)).isEqualTo(5);
    assertThat(sequence.getM(2)).isEqualTo(11);
  }

  @Test
  void curvePolygonKeepsClosedCurveRingAndExactControls() throws Exception {
    CurvePolygon polygon =
        (CurvePolygon)
            codec.readText("CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0))");
    CircularString ring = (CircularString) polygon.getCurveRings().get(0);
    assertThat(ring.getControlPoints()).hasSize(5);
    assertThat(ring.getControlPoints()[0].x).isEqualTo(0);
    assertThat(ring.getControlPoints()[0].y).isEqualTo(0);
    assertThat(ring.getControlPoints()[4].x).isEqualTo(0);
    assertThat(ring.getControlPoints()[4].y).isEqualTo(0);
    assertThat(ring.getNumPoints()).isGreaterThan(5);
  }

  @Test
  void collectionWithNestedCurveKeepsConcreteTypesAndOrder() throws Exception {
    GeometryCollection collection =
        (GeometryCollection)
            codec.readText(
                "GEOMETRYCOLLECTION (POINT (1 2), CIRCULARSTRING (1 0, 0 1, -1 0), CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0)))");
    assertThat(collection.getNumGeometries()).isEqualTo(3);
    assertThat(collection.getGeometryN(0)).isInstanceOf(Point.class);
    assertThat(collection.getGeometryN(1)).isInstanceOf(CircularString.class);
    assertThat(collection.getGeometryN(2)).isInstanceOf(CurvePolygon.class);
    assertThat(((CurvePolygon) collection.getGeometryN(2)).getCurveRings().get(0))
        .isInstanceOf(CircularString.class);
  }

  @Test
  void nativeCopyIsExactAndDoesNotShareControlSequences() throws Exception {
    GeometricOwnership.assertCopyIsIndependent(
        codec,
        codec.readText(
            "CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0))"));
    GeometricOwnership.assertCopyIsIndependent(
        codec, codec.readText("MULTICURVE ((0 0, 5 5), CIRCULARSTRING (0 0, 2 2, 4 0))"));
    GeometricOwnership.assertCopyIsIndependent(
        codec, codec.readText("POINT M (1 2 9)"));
  }

  @Test
  void unconnectedCompoundCurvesAndOpenCurveRingsFail() {
    assertCode(
        () ->
            codec.readText(
                "COMPOUNDCURVE ((0 0, 1 1), CIRCULARSTRING (2 1, 3 2, 4 1))"),
        GeometryCodecErrorCode.GC_INVALID_WKT);
    assertCode(
        () ->
            codec.readText(
                "CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 1 1))"),
        GeometryCodecErrorCode.GC_INVALID_WKT);
  }

  @Test
  void unsupportedEmptyCurveMembersAreNamedErrors() {
    assertCode(
        () -> codec.readText("MULTICURVE (CIRCULARSTRING EMPTY)"),
        GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE);
  }

  @Test
  void multiCurveAndMultiSurfaceWriterHonourEachMemberLayout() throws Exception {
    MultiCurve multiCurve =
        (MultiCurve)
            codec.readText(
                "MULTICURVE (LINESTRING Z (10 0 NaN, 12 2 NaN), CIRCULARSTRING (0 0, 2 2, 4 0))");
    assertThat(GeometryLayoutSupport.aggregateLayout(multiCurve))
        .isEqualTo(CoordinateLayout.XYZ);
    String text = codec.writeText(multiCurve, WktWriteOptions.wkt());
    assertThat(text).startsWith("MULTICURVE (LINESTRING Z").contains("CIRCULARSTRING (");
    GeometryAssertions.assertGeometryPreserved(multiCurve, codec.readText(text));

    MultiSurface multiSurface =
        (MultiSurface)
            codec.readText(
                "MULTISURFACE (POLYGON Z ((10 10 NaN, 14 10 NaN, 14 14 NaN, 10 10 NaN)), CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0)))");
    byte[] wkb = codec.writeBinary(multiSurface, WkbWriteOptions.standardFieldBinary());
    GeometryAssertions.assertGeometryPreserved(multiSurface, codec.readBinary(wkb));
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

  private static final class GeometricOwnership {
    private static void assertCopyIsIndependent(GeometryCodec codec, Geometry source)
        throws Exception {
      Geometry copy = codec.copyExact(source);
      GeometryAssertions.assertGeometryPreserved(source, copy);
      assertThat(copy).isNotSameAs(source);
      if (source instanceof CircularString sourceCurve) {
        CircularString copyCurve = (CircularString) copy;
        assertThat(copyCurve.getControlPointSequence())
            .isNotSameAs(sourceCurve.getControlPointSequence());
        assertThat(copyCurve.getControlPoints()[0]).isNotSameAs(sourceCurve.getControlPoints()[0]);
      }
      source.setSRID(2056);
      assertThat(copy.getSRID()).isZero();
    }
  }
}
