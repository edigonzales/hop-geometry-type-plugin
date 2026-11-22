package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

class GeometryCodecNonMutationTest {
  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());

  @Test
  void everyWriteModeLeavesSourceGeometryAndChildSridsUnchanged() throws Exception {
    Geometry source =
        codec.readText(
            "GEOMETRYCOLLECTION (POINT (1 2), CIRCULARSTRING (1 0, 0 1, -1 0), CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0)))");
    source.setSRID(2056);
    ((Geometry) source.getGeometryN(1)).setSRID(2056);
    Geometry snapshot = codec.copyExact(source);

    codec.writeText(source, WktWriteOptions.wkt());
    codec.writeText(source, WktWriteOptions.legacy());
    codec.writeText(source, WktWriteOptions.ewkt());
    codec.writeBinary(source, WkbWriteOptions.standardFieldBinary());
    codec.writeBinary(source, WkbWriteOptions.iso(GeometryByteOrder.LITTLE_ENDIAN));
    codec.writeBinary(source, WkbWriteOptions.iso(GeometryByteOrder.BIG_ENDIAN));
    codec.writeBinary(source, WkbWriteOptions.ewkb(GeometryByteOrder.LITTLE_ENDIAN));
    codec.writeBinary(source, WkbWriteOptions.ewkb(GeometryByteOrder.BIG_ENDIAN));
    codec.writeHex(source, WkbWriteOptions.standardFieldBinary());
    codec.validateSupportedGeometry(source);

    GeometryAssertions.assertGeometryPreserved(snapshot, source);
    assertThat(source.getSRID()).isEqualTo(2056);
  }

  @Test
  void copyExactDoesNotShareSequencesControlsOrChildObjects() throws Exception {
    CurvePolygon source =
        (CurvePolygon)
            codec.readText("CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0))");
    CurvePolygon copy = (CurvePolygon) codec.copyExact(source);
    CircularString sourceRing = (CircularString) source.getCurveRings().get(0);
    CircularString copyRing = (CircularString) copy.getCurveRings().get(0);
    assertThat(copyRing).isNotSameAs(sourceRing);
    assertThat(copyRing.getControlPointSequence())
        .isNotSameAs(sourceRing.getControlPointSequence());
    assertThat(copyRing.getControlPoints()[0]).isNotSameAs(sourceRing.getControlPoints()[0]);

    copyRing.getControlPointSequence().setOrdinate(0, 0, 999);
    assertThat(sourceRing.getControlPointSequence().getX(0)).isZero();
  }

  @Test
  void sridReplacementWorksOnCopiesAndLeavesSourceUntouched() throws Exception {
    Geometry source = codec.readText("POINT (1 2)");
    source.setSRID(2056);
    Geometry replaced = GeometrySridSupport.copyWithSrid(source, 4326);
    assertThat(source.getSRID()).isEqualTo(2056);
    assertThat(replaced.getSRID()).isEqualTo(4326);
    assertThat(codec.writeText(replaced, WktWriteOptions.ewkt()))
        .isEqualTo("SRID=4326;POINT (1 2)");
  }

  @Test
  void copyExactRejectsMixedSridWithoutMutatingSource() throws Exception {
    Geometry source =
        codec.readText("GEOMETRYCOLLECTION (POINT (1 2), POINT (3 4))");
    ((Geometry) source.getGeometryN(0)).setSRID(2056);
    ((Geometry) source.getGeometryN(1)).setSRID(4326);
    assertThatThrownBy(() -> codec.copyExact(source))
        .isInstanceOf(GeometryCodecException.class);
    assertThat(((Geometry) source.getGeometryN(0)).getSRID()).isEqualTo(2056);
    assertThat(((Geometry) source.getGeometryN(1)).getSRID()).isEqualTo(4326);
  }
}
