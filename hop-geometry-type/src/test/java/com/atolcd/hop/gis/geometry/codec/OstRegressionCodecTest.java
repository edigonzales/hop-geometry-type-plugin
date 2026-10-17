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
import org.locationtech.jts.geom.Point;

/** Explicit regression cases OR01–OR10 from specification chapter 04 §10. */
class OstRegressionCodecTest {
  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());

  @Test
  void or01EmptyGeometriesThroughPublicDispatcherAndBinaryPath() throws Exception {
    for (String text : new String[] {"POINT EMPTY", "LINESTRING EMPTY", "POLYGON EMPTY"}) {
      Geometry geometry = codec.readText(text);
      assertThat(geometry.isEmpty()).isTrue();
      Geometry binary = codec.readBinary(codec.writeBinary(geometry, WkbWriteOptions.standardFieldBinary()));
      GeometryAssertions.assertGeometryPreserved(geometry, binary);
    }
  }

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
    CircularString curve =
        (CircularString) codec.readText("CIRCULARSTRING (+1e0 0, 0 1E+0, -1 0)");
    assertThat(curve.getControlPoints())
        .extracting(c -> c.x, c -> c.y)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1.0, 0.0),
            org.assertj.core.groups.Tuple.tuple(0.0, 1.0),
            org.assertj.core.groups.Tuple.tuple(-1.0, 0.0));
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
  void or06IsoDimensionOffsetsForCurveTypesInBothByteOrders() throws Exception {
    String[] fixtures = {
      "01F003000003000000000000000000F03F000000000000000000000000000008400000000000000000000000000000F03F0000000000001040000000000000F0BF00000000000000000000000000001440",
      "00000003F0000000033FF00000000000000000000000000000400800000000000000000000000000003FF00000000000004010000000000000BFF000000000000000000000000000004014000000000000",
      "01D807000003000000000000000000F03F000000000000000000000000000024400000000000000000000000000000F03F0000000000003440000000000000F0BF00000000000000000000000000003E40",
      "00000007D8000000033FF00000000000000000000000000000402400000000000000000000000000003FF00000000000004034000000000000BFF00000000000000000000000000000403E000000000000",
      "01C00B000003000000000000000000F03F0000000000000000000000000000084000000000000024400000000000000000000000000000F03F00000000000010400000000000003440000000000000F0BF000000000000000000000000000014400000000000003E40",
      "0000000BC0000000033FF000000000000000000000000000004008000000000000402400000000000000000000000000003FF000000000000040100000000000004034000000000000BFF000000000000000000000000000004014000000000000403E000000000000"
    };
    for (String fixture : fixtures) {
      CircularString curve = (CircularString) codec.readBinary(HexFormat.of().parseHex(fixture));
      assertThat(curve.getControlPoints()).hasSize(3);
      assertThat(curve.getControlPointSequence().getDimension()).isIn(3, 4);
    }
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
  void or08MIsNotZAndDeclaredDimensionIsNotAnEndlicheValue() throws Exception {
    Point xym = (Point) codec.readText("POINT M (1 2 9)");
    assertThat(xym.getCoordinateSequence().hasM()).isTrue();
    assertThat(xym.getCoordinateSequence().hasZ()).isFalse();
    assertThat(xym.getCoordinateSequence().getM(0)).isEqualTo(9);

    Point xyzNan = (Point) codec.readText("POINT Z (1 2 NaN)");
    assertThat(xyzNan.getCoordinateSequence().hasZ()).isTrue();
    assertThat(xyzNan.getCoordinateSequence().getZ(0)).isNaN();

    Point xyzm = (Point) codec.readText("POINT ZM (1 2 NaN 9)");
    assertThat(xyzm.getCoordinateSequence().getDimension()).isEqualTo(4);
    assertThat(xyzm.getCoordinateSequence().getMeasures()).isEqualTo(1);

    Geometry xymBinary =
        codec.readBinary(HexFormat.of().parseHex("00000007D13FF000000000000040000000000000004010000000000000"));
    assertThat(((Point) xymBinary).getCoordinateSequence().hasM()).isTrue();
    assertThat(((Point) xymBinary).getCoordinateSequence().getM(0)).isEqualTo(4);
  }

  @Test
  void or09WriterOptionsApplyToEveryGeometryTypeIncludingCollections() throws Exception {
    Geometry linear = codec.readText("POINT (1 2)");
    linear.setSRID(2056);
    assertThat(codec.writeText(linear, WktWriteOptions.wkt())).isEqualTo("POINT (1 2)");
    assertThat(codec.writeText(linear, WktWriteOptions.ewkt())).isEqualTo("SRID=2056;POINT (1 2)");

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

  @Test
  void or10WritersDoNotMutateSourceSridLayoutOrControls() throws Exception {
    Geometry source =
        codec.readText("CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0))");
    source.setSRID(2056);
    Geometry snapshot = codec.copyExact(source);
    codec.writeText(source, WktWriteOptions.ewkt());
    codec.writeBinary(source, WkbWriteOptions.ewkb(GeometryByteOrder.LITTLE_ENDIAN));
    codec.writeBinary(source, WkbWriteOptions.iso(GeometryByteOrder.BIG_ENDIAN));
    codec.writeHex(source, WkbWriteOptions.standardFieldBinary());
    GeometryAssertions.assertGeometryPreserved(snapshot, source);
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
