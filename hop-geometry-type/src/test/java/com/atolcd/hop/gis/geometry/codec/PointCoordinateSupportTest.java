package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

class PointCoordinateSupportTest {
  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());
  private final GeometryFactory factory = new GeometryFactory();

  @Test
  void createsAllLayoutsAtTheCorrectOrdinatePositions() throws Exception {
    Point xy =
        PointCoordinateSupport.create(
            new PointCoordinates(1d, 2d, null, null, CoordinateLayout.XY), 2056, factory);
    assertThat(xy.getCoordinateSequence().getDimension()).isEqualTo(2);
    assertThat(xy.getCoordinateSequence().getMeasures()).isZero();
    assertThat(xy.getSRID()).isEqualTo(2056);

    Point xyz =
        PointCoordinateSupport.create(
            new PointCoordinates(1d, 2d, 3d, null, CoordinateLayout.XYZ), 0, factory);
    assertThat(xyz.getCoordinateSequence().getDimension()).isEqualTo(3);
    assertThat(xyz.getCoordinateSequence().getZ(0)).isEqualTo(3);
    assertThat(xyz.getCoordinateSequence().getM(0)).isNaN();

    Point xym =
        PointCoordinateSupport.create(
            new PointCoordinates(1d, 2d, null, 4d, CoordinateLayout.XYM), 0, factory);
    assertThat(xym.getCoordinateSequence().getDimension()).isEqualTo(3);
    assertThat(xym.getCoordinateSequence().getMeasures()).isEqualTo(1);
    assertThat(xym.getCoordinateSequence().getM(0)).isEqualTo(4);
    assertThat(xym.getCoordinateSequence().getZ(0)).isNaN();

    Point xyzm =
        PointCoordinateSupport.create(
            new PointCoordinates(1d, 2d, 3d, 4d, CoordinateLayout.XYZM), 0, factory);
    assertThat(xyzm.getCoordinateSequence().getDimension()).isEqualTo(4);
    assertThat(xyzm.getCoordinateSequence().getZ(0)).isEqualTo(3);
    assertThat(xyzm.getCoordinateSequence().getM(0)).isEqualTo(4);
  }

  @Test
  void missingOptionalOrdinatesKeepTheDeclaredLayout() throws Exception {
    Point xyz =
        PointCoordinateSupport.create(
            new PointCoordinates(1d, 2d, null, null, CoordinateLayout.XYZ), 0, factory);
    assertThat(xyz.getCoordinateSequence().hasZ()).isTrue();
    assertThat(xyz.getCoordinateSequence().getZ(0)).isNaN();

    Point xyzm =
        PointCoordinateSupport.create(
            new PointCoordinates(1d, 2d, Double.NaN, null, CoordinateLayout.XYZM), 0, factory);
    assertThat(xyzm.getCoordinateSequence().hasZ()).isTrue();
    assertThat(xyzm.getCoordinateSequence().hasM()).isTrue();
    assertThat(xyzm.getCoordinateSequence().getZ(0)).isNaN();
    assertThat(xyzm.getCoordinateSequence().getM(0)).isNaN();
  }

  @Test
  void completeNullTupleReturnsNullAndPartialTuplesFail() throws Exception {
    assertThat(
            PointCoordinateSupport.create(
                new PointCoordinates(null, null, null, null, CoordinateLayout.XYZ), 0, factory))
        .isNull();
    assertCode(
        () ->
            PointCoordinateSupport.create(
                new PointCoordinates(null, 2d, null, null, CoordinateLayout.XY), 0, factory),
        GeometryCodecErrorCode.GC_PARTIAL_COORDINATE_NULL);
    assertCode(
        () ->
            PointCoordinateSupport.create(
                new PointCoordinates(null, null, 3d, null, CoordinateLayout.XYZ), 0, factory),
        GeometryCodecErrorCode.GC_PARTIAL_COORDINATE_NULL);
    assertCode(
        () ->
            PointCoordinateSupport.create(
                new PointCoordinates(null, null, null, 4d, CoordinateLayout.XYM), 0, factory),
        GeometryCodecErrorCode.GC_PARTIAL_COORDINATE_NULL);
  }

  @Test
  void nonFiniteXYAndInfinityFailButInfinityInOptionalValuesFailsToo() {
    assertCode(
        () ->
            PointCoordinateSupport.create(
                new PointCoordinates(Double.NaN, 2d, null, null, CoordinateLayout.XY), 0, factory),
        GeometryCodecErrorCode.GC_NONFINITE_COORDINATE);
    assertCode(
        () ->
            PointCoordinateSupport.create(
                new PointCoordinates(1d, Double.POSITIVE_INFINITY, null, null, CoordinateLayout.XY),
                0,
                factory),
        GeometryCodecErrorCode.GC_NONFINITE_COORDINATE);
    assertCode(
        () ->
            PointCoordinateSupport.create(
                new PointCoordinates(1d, 2d, Double.POSITIVE_INFINITY, null, CoordinateLayout.XYZ),
                0,
                factory),
        GeometryCodecErrorCode.GC_NONFINITE_COORDINATE);
  }

  @Test
  void suppliesValuesThatDoNotBelongToTheLayoutAreRejected() {
    assertCode(
        () ->
            PointCoordinateSupport.create(
                new PointCoordinates(1d, 2d, 3d, null, CoordinateLayout.XY), 0, factory),
        GeometryCodecErrorCode.GC_TYPE_MISMATCH);
    assertCode(
        () ->
            PointCoordinateSupport.create(
                new PointCoordinates(1d, 2d, null, 4d, CoordinateLayout.XYZ), 0, factory),
        GeometryCodecErrorCode.GC_TYPE_MISMATCH);
  }

  @Test
  void extractsAllPointLayoutsAndMissingValuesAsNull() throws Exception {
    PointCoordinates xym = PointCoordinateSupport.extract(codec.readText("POINT M (1 2 9)"));
    assertThat(xym.layout()).isEqualTo(CoordinateLayout.XYM);
    assertThat(xym.x()).isEqualTo(1);
    assertThat(xym.y()).isEqualTo(2);
    assertThat(xym.z()).isNull();
    assertThat(xym.m()).isEqualTo(9);

    PointCoordinates xyzNan = PointCoordinateSupport.extract(codec.readText("POINT Z (1 2 NaN)"));
    assertThat(xyzNan.layout()).isEqualTo(CoordinateLayout.XYZ);
    assertThat(xyzNan.z()).isNull();

    PointCoordinates xyzm = PointCoordinateSupport.extract(codec.readText("POINT ZM (1 2 3 4)"));
    assertThat(xyzm.z()).isEqualTo(3);
    assertThat(xyzm.m()).isEqualTo(4);
  }

  @Test
  void extractsNullAndEmptyPointAccordingToContract() throws Exception {
    PointCoordinates nullPoint = PointCoordinateSupport.extract(null);
    assertThat(nullPoint.layout()).isEqualTo(CoordinateLayout.XY);
    assertThat(nullPoint.x()).isNull();

    PointCoordinates empty = PointCoordinateSupport.extract(codec.readText("POINT Z EMPTY"));
    assertThat(empty.layout()).isEqualTo(CoordinateLayout.XYZ);
    assertThat(empty.x()).isNull();
    assertThat(empty.y()).isNull();
    assertThat(empty.z()).isNull();
    assertThat(empty.m()).isNull();
  }

  @Test
  void nonPointsAndEmptyNonPointsAreRejected() throws Exception {
    for (String text :
        new String[] {
          "LINESTRING (0 0, 1 1)",
          "LINESTRING EMPTY",
          "MULTIPOINT ((1 2))",
          "POLYGON ((0 0, 1 0, 1 1, 0 0))"
        }) {
      assertCode(
          () -> PointCoordinateSupport.extract(codec.readText(text)),
          GeometryCodecErrorCode.GC_NOT_A_POINT);
    }
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
