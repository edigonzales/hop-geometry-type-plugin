package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import java.util.HexFormat;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;

/** Regression cases from specification chapter 04 §10. */
class OstRegressionCodecTest {
  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());

  @Test
  void or02DimensionMarkersOnRealCurvesAndMixedCaseTypeTokens() throws Exception {
    CircularString z =
        (CircularString)
            codec.readText("cIrCuLaRsTrInG z (1 0 3, 0 1 3, -1 0 3)");
    assertThat(z.getControlPointSequence().hasZ()).isTrue();
    assertThat(z.getControlPoints()).hasSize(3);
    assertThat(z.getControlPoints()[0].getZ()).isEqualTo(3);

    CircularString m = (CircularString) codec.readText("CIRCULARSTRING M (1 0 3, 0 1 3, -1 0 3)");
    assertThat(m.getControlPointSequence().hasM()).isTrue();
    assertThat(m.getControlPointSequence().getM(0)).isEqualTo(3);

    CircularString zm =
        (CircularString) codec.readText("SRID=2056;CIRCULARSTRING ZM (1 0 3 4, 0 1 3 4, -1 0 3 4)");
    assertThat(zm.getSRID()).isEqualTo(2056);
    assertThat(zm.getControlPointSequence().getDimension()).isEqualTo(4);

    assertCode(
        () -> codec.readText("CIRCULARSTRING EMPTY"),
        GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE);
  }

  @Test
  void or03NumberLexicographyInCurvesAndLinearGeometries() throws Exception {
    Geometry line = codec.readText("LINESTRING (.5 1., +1.25 -1E-3)");
    assertThat(((org.locationtech.jts.geom.LineString) line).getCoordinateN(0).x).isEqualTo(0.5);
    assertThat(((org.locationtech.jts.geom.LineString) line).getCoordinateN(1).y).isEqualTo(-0.001);

    for (String invalid :
        new String[] {
          "CIRCULARSTRING (1e 0, 0 1, -1 0)",
          "CIRCULARSTRING (1e+ 0, 0 1, -1 0)",
          "CIRCULARSTRING (1d 0, 0 1, -1 0)",
          "CIRCULARSTRING (0x1p3 0, 0 1, -1 0)",
          "CIRCULARSTRING (1e999 0, 0 1, -1 0)",
          "CIRCULARSTRING (1 0, 0 1, -1 0) // comment"
        }) {
      assertThatThrownBy(() -> codec.readText(invalid))
          .as(invalid)
          .isInstanceOf(GeometryCodecException.class);
    }
  }

  @Test
  void or04CloserAndEofChecksAndStateReset() throws Exception {
    for (String invalid :
        new String[] {
          "CIRCULARSTRING (1 0, 0 1, -1 0) garbage",
          "POINT (1 2) POINT (3 4)",
          "COMPOUNDCURVE ((0 0, 1 1) BOGUS)"
        }) {
      assertCode(() -> codec.readText(invalid), GeometryCodecErrorCode.GC_INVALID_WKT);
    }
    assertThat(codec.readText("CIRCULARSTRING (1 0, 0 1, -1 0)"))
        .isInstanceOf(CircularString.class);
  }

  @Test
  void or05UnknownMembersAndInvalidEwktPrefixesAreNotIgnored() {
    assertCode(
        () -> codec.readText("MULTISURFACE (BOGUS)"), GeometryCodecErrorCode.GC_INVALID_WKT);
    assertThatThrownBy(() -> codec.readText("FOO=2056;POINT (1 2)"))
        .isInstanceOf(GeometryCodecException.class);
    assertThatThrownBy(() -> codec.readText("SRID=1;SRID=2;POINT (1 2)"))
        .isInstanceOf(GeometryCodecException.class);
    assertThatThrownBy(() -> codec.readText("SRID=2056;POINT (1 2);"))
        .isInstanceOf(GeometryCodecException.class);
  }

  @Test
  void or07CurvesUnderOrdinaryCollectionRootsThroughAllPaths() throws Exception {
    String text =
        "GEOMETRYCOLLECTION (POINT (1 2), CIRCULARSTRING (1 0, 0 1, -1 0), CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0)))";
    Geometry source = codec.readText(text);
    Geometry fromText = codec.readText(codec.writeText(source, WktWriteOptions.wkt()));
    Geometry fromBinary =
        codec.readBinary(codec.writeBinary(source, WkbWriteOptions.standardFieldBinary()));
    Geometry copied = codec.copyExact(source);
    for (Geometry result : new Geometry[] {fromText, fromBinary, copied}) {
      GeometryAssertions.assertGeometryPreserved(source, result);
      assertThat(((GeometryCollection) result).getGeometryN(1)).isInstanceOf(CircularString.class);
      assertThat(((GeometryCollection) result).getGeometryN(2)).isInstanceOf(CurvePolygon.class);
    }
  }

  @Test
  void or09WriterOptionsApplyToEveryGeometryTypeIncludingCollections() throws Exception {
    Geometry collection =
        codec.readText("GEOMETRYCOLLECTION (POINT (1 2), CIRCULARSTRING (1 0, 0 1, -1 0))");
    collection.setSRID(2056);
    String isoLe =
        hex(codec.writeBinary(collection, WkbWriteOptions.iso(GeometryByteOrder.LITTLE_ENDIAN)));
    String isoBe =
        hex(codec.writeBinary(collection, WkbWriteOptions.iso(GeometryByteOrder.BIG_ENDIAN)));
    String ewkbLe =
        hex(codec.writeBinary(collection, WkbWriteOptions.ewkb(GeometryByteOrder.LITTLE_ENDIAN)));
    String ewkbBe =
        hex(codec.writeBinary(collection, WkbWriteOptions.ewkb(GeometryByteOrder.BIG_ENDIAN)));
    assertThat(isoLe)
        .isEqualTo(
            "0107000000020000000101000000000000000000F03F0000000000000040010800000003000000000000000000F03F00000000000000000000000000000000000000000000F03F000000000000F0BF0000000000000000");
    assertThat(isoBe)
        .isEqualTo(
            "00000000070000000200000000013FF000000000000040000000000000000000000008000000033FF0000000000000000000000000000000000000000000003FF0000000000000BFF00000000000000000000000000000");
    assertThat(ewkbLe)
        .isEqualTo(
            "010700002008080000020000000101000000000000000000F03F0000000000000040010800000003000000000000000000F03F00000000000000000000000000000000000000000000F03F000000000000F0BF0000000000000000");
    assertThat(ewkbBe)
        .isEqualTo(
            "0020000007000008080000000200000000013FF000000000000040000000000000000000000008000000033FF0000000000000000000000000000000000000000000003FF0000000000000BFF00000000000000000000000000000");
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes).toUpperCase();
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
