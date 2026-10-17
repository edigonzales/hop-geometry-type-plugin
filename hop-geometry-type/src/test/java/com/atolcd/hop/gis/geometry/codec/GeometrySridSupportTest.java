package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import com.atolcd.hop.gis.geometry.curve.MultiCurve;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.Point;

class GeometrySridSupportTest {
  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());

  @Test
  void resolvesNullAsUnknownAndSingleSridDirectly() throws Exception {
    assertThat(GeometrySridSupport.resolveConsistentSrid(null)).isZero();
    Point point = (Point) codec.readText("POINT (1 2)");
    assertThat(GeometrySridSupport.resolveConsistentSrid(point)).isZero();
    point.setSRID(2056);
    assertThat(GeometrySridSupport.resolveConsistentSrid(point)).isEqualTo(2056);
  }

  @Test
  void inheritsKnownRootAndPromotesUniformChildSrid() throws Exception {
    Point root = (Point) codec.readText("POINT (1 2)");
    root.setSRID(2056);
    assertThat(GeometrySridSupport.resolveConsistentSrid(root)).isEqualTo(2056);

    GeometryCollection rootUnknown =
        (GeometryCollection)
            codec.readText("GEOMETRYCOLLECTION (POINT (1 2), POINT (3 4))");
    ((Geometry) rootUnknown.getGeometryN(0)).setSRID(2056);
    ((Geometry) rootUnknown.getGeometryN(1)).setSRID(0);
    assertThat(GeometrySridSupport.resolveConsistentSrid(rootUnknown)).isEqualTo(2056);
  }

  @Test
  void rejectsMixedPositiveAndNegativeSrids() throws Exception {
    GeometryCollection mixed =
        (GeometryCollection)
            codec.readText("GEOMETRYCOLLECTION (POINT (1 2), POINT (3 4))");
    ((Geometry) mixed.getGeometryN(0)).setSRID(2056);
    ((Geometry) mixed.getGeometryN(1)).setSRID(4326);
    assertCode(
        () -> GeometrySridSupport.resolveConsistentSrid(mixed),
        GeometryCodecErrorCode.GC_MIXED_SRID);

    Point negative = (Point) codec.readText("POINT (1 2)");
    negative.setSRID(-1);
    assertCode(
        () -> GeometrySridSupport.resolveConsistentSrid(negative),
        GeometryCodecErrorCode.GC_INVALID_SRID);
  }

  @Test
  void copyWithSridChangesOnlyTheCopyAndAssignsRecursively() throws Exception {
    Geometry geometry =
        codec.readText(
            "GEOMETRYCOLLECTION (POINT (1 2), CIRCULARSTRING (1 0, 0 1, -1 0), CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0)))");
    geometry.setSRID(2056);
    Geometry copy = GeometrySridSupport.copyWithSrid(geometry, 4326);
    assertThat(copy).isNotSameAs(geometry);
    assertThat(copy.getSRID()).isEqualTo(4326);
    assertThat(geometry.getSRID()).isEqualTo(2056);
    GeometryAssertions.assertGeometryPreserved(geometry, copy, false);
    GeometryCollection collection = (GeometryCollection) copy;
    for (int i = 0; i < collection.getNumGeometries(); i++) {
      assertThat(collection.getGeometryN(i).getSRID()).isEqualTo(4326);
    }
    CurvePolygon curvePolygon = (CurvePolygon) collection.getGeometryN(2);
    assertThat(curvePolygon.getCurveRings().get(0).getSRID()).isEqualTo(4326);

    MultiCurve multiCurve =
        (MultiCurve)
            codec.readText("MULTICURVE ((0 0, 5 5), CIRCULARSTRING (0 0, 2 2, 4 0))");
    multiCurve.setSRID(2056);
    MultiCurve copied = (MultiCurve) GeometrySridSupport.copyWithSrid(multiCurve, 4326);
    assertThat(copied.getCurves()).allSatisfy(curve -> assertThat(curve.getSRID()).isEqualTo(4326));
    assertThat(multiCurve.getCurves()).allSatisfy(curve -> assertThat(curve.getSRID()).isZero());

    CircularString circular = (CircularString) codec.readText("CIRCULARSTRING (1 0, 0 1, -1 0)");
    circular.setSRID(2056);
    CircularString circularCopy = (CircularString) GeometrySridSupport.copyWithSrid(circular, 4326);
    assertThat(circularCopy.getSRID()).isEqualTo(4326);
    assertThat(circular.getSRID()).isEqualTo(2056);
    assertThat(circularCopy.getControlPointSequence()).isNotSameAs(circular.getControlPointSequence());
  }

  @Test
  void nullAndNegativeTargetsAreHandledExplicitly() throws Exception {
    assertThat(GeometrySridSupport.copyWithSrid(null, 4326)).isNull();
    assertCode(
        () -> GeometrySridSupport.copyWithSrid(null, -1),
        GeometryCodecErrorCode.GC_INVALID_SRID);
  }

  @Test
  void writerDoesNotMutateSourceSrid() throws Exception {
    Geometry geometry = codec.readText("CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0))");
    geometry.setSRID(2056);
    int before = geometry.getSRID();
    String ewkt = codec.writeText(geometry, WktWriteOptions.ewkt());
    byte[] ewkb = codec.writeBinary(geometry, WkbWriteOptions.ewkb(GeometryByteOrder.BIG_ENDIAN));
    codec.writeHex(geometry, WkbWriteOptions.standardFieldBinary());
    assertThat(geometry.getSRID()).isEqualTo(before);
    assertThat(ewkt).startsWith("SRID=2056;");
    assertThat(ewkb).isNotEmpty();
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
