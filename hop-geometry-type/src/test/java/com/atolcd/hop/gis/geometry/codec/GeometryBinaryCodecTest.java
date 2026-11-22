package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CompoundCurve;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import com.atolcd.hop.gis.geometry.curve.MultiCurve;
import com.atolcd.hop.gis.geometry.curve.MultiSurface;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

class GeometryBinaryCodecTest {
  private static final String XY_ISO_BE =
      "00000000013FF00000000000004000000000000000";
  private static final String XY_ISO_LE =
      "0101000000000000000000F03F0000000000000040";
  private static final String XY_EWKB_LE_SRID =
      "010100002008080000000000000000F03F0000000000000040";
  private static final String XYZ_ISO_LE =
      "01E9030000000000000000F03F00000000000000400000000000000840";
  private static final String XYM_ISO_BE =
      "00000007D13FF000000000000040000000000000004010000000000000";
  private static final String XYZM_ISO_LE =
      "01B90B0000000000000000F03F000000000000004000000000000008400000000000001040";
  private static final String XYZM_EWKB_BE_SRID =
      "00E0000001000008083FF0000000000000400000000000000040080000000000004010000000000000";
  private static final String XY_EWKB_BE_SRID0 =
      "0020000001000000003FF00000000000004000000000000000";

  private static final String CIRCULAR_XY_LE =
      "010800000003000000000000000000F03F00000000000000000000000000000000000000000000F03F000000000000F0BF0000000000000000";
  private static final String CIRCULAR_XYZM_EWKB =
      "00C0000008000000033FF000000000000000000000000000004008000000000000402400000000000000000000000000003FF000000000000040100000000000004034000000000000BFF000000000000000000000000000004014000000000000403E000000000000";
  private static final String COMPOUND_XY_LE =
      "01090000000200000001020000000200000000000000000000000000000000000000000000000000F03F0000000000000000010800000003000000000000000000F03F00000000000000000000000000000040000000000000F03F00000000000008400000000000000000";
  private static final String CURVEPOLYGON_XY_LE =
      "010A000000010000000108000000050000000000000000000000000000000000000000000000000010400000000000000000000000000000104000000000000010400000000000000000000000000000104000000000000000000000000000000000";
  private static final String MULTICURVE_XY_LE =
      "010B000000020000000102000000020000000000000000002440000000000000000000000000000028400000000000000040010800000003000000000000000000000000000000000000000000000000000040000000000000004000000000000010400000000000000000";
  private static final String MULTISURFACE_XY_LE =
      "010C000000020000000103000000010000000400000000000000000000000000000000000000000000000000104000000000000000000000000000000040000000000000104000000000000000000000000000000000010A0000000100000001080000000500000000000000000024400000000000000000000000000000284000000000000000400000000000002C400000000000000000000000000000284000000000000000C000000000000024400000000000000000";
  private static final String COLLECTION_WITH_CURVES =
      "0107000000030000000101000000000000000000F03F0000000000000040010800000003000000000000000000F03F00000000000000000000000000000000000000000000F03F000000000000F0BF0000000000000000010A000000010000000108000000050000000000000000000000000000000000000000000000000010400000000000000000000000000000104000000000000010400000000000000000000000000000104000000000000000000000000000000000";
  private static final String MIXED_ORDER_COLLECTION =
      "01070000000200000000000000013FF00000000000004000000000000000010100000000000000000008400000000000001040";

  private static final String CIRC_1_LE =
      "01F003000003000000000000000000F03F000000000000000000000000000008400000000000000000000000000000F03F0000000000001040000000000000F0BF00000000000000000000000000001440";
  private static final String CIRC_1_BE =
      "00000003F0000000033FF00000000000000000000000000000400800000000000000000000000000003FF00000000000004010000000000000BFF000000000000000000000000000004014000000000000";
  private static final String CIRC_2_LE =
      "01D807000003000000000000000000F03F000000000000000000000000000024400000000000000000000000000000F03F0000000000003440000000000000F0BF00000000000000000000000000003E40";
  private static final String CIRC_2_BE =
      "00000007D8000000033FF00000000000000000000000000000402400000000000000000000000000003FF00000000000004034000000000000BFF00000000000000000000000000000403E000000000000";
  private static final String CIRC_3_LE =
      "01C00B000003000000000000000000F03F0000000000000000000000000000084000000000000024400000000000000000000000000000F03F00000000000010400000000000003440000000000000F0BF000000000000000000000000000014400000000000003E40";
  private static final String CIRC_3_BE =
      "0000000BC0000000033FF000000000000000000000000000004008000000000000402400000000000000000000000000003FF000000000000040100000000000004034000000000000BFF000000000000000000000000000004014000000000000403E000000000000";

  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());

  @Test
  void readsIndependentPointFixturesWithExactLayoutAndSrid() throws Exception {
    assertPoint(XY_ISO_BE, CoordinateLayout.XY, 1, 2, Double.NaN, Double.NaN, 0);
    assertPoint(XY_ISO_LE, CoordinateLayout.XY, 1, 2, Double.NaN, Double.NaN, 0);
    assertPoint(XY_EWKB_LE_SRID, CoordinateLayout.XY, 1, 2, Double.NaN, Double.NaN, 2056);
    assertPoint(XYZ_ISO_LE, CoordinateLayout.XYZ, 1, 2, 3, Double.NaN, 0);
    assertPoint(XYM_ISO_BE, CoordinateLayout.XYM, 1, 2, Double.NaN, 4, 0);
    assertPoint(XYZM_ISO_LE, CoordinateLayout.XYZM, 1, 2, 3, 4, 0);
    assertPoint(XYZM_EWKB_BE_SRID, CoordinateLayout.XYZM, 1, 2, 3, 4, 2056);
    assertPoint(XY_EWKB_BE_SRID0, CoordinateLayout.XY, 1, 2, Double.NaN, Double.NaN, 0);
  }

  @Test
  void writesIndependentPointFixturesExactly() throws Exception {
    Point xy = (Point) codec.readText("POINT (1 2)");
    assertThat(hex(codec.writeBinary(xy, WkbWriteOptions.iso(GeometryByteOrder.BIG_ENDIAN))))
        .isEqualTo(XY_ISO_BE);
    assertThat(hex(codec.writeBinary(xy, WkbWriteOptions.iso(GeometryByteOrder.LITTLE_ENDIAN))))
        .isEqualTo(XY_ISO_LE);
    assertThat(
            hex(
                codec.writeBinary(
                    xy, WkbWriteOptions.ewkb(GeometryByteOrder.BIG_ENDIAN, SridEncoding.ALWAYS))))
        .isEqualTo(XY_EWKB_BE_SRID0);

    Point xyz = (Point) codec.readText("POINT Z (1 2 3)");
    assertThat(hex(codec.writeBinary(xyz, WkbWriteOptions.iso(GeometryByteOrder.LITTLE_ENDIAN))))
        .isEqualTo(XYZ_ISO_LE);

    Point xym = (Point) codec.readText("POINT M (1 2 4)");
    assertThat(hex(codec.writeBinary(xym, WkbWriteOptions.iso(GeometryByteOrder.BIG_ENDIAN))))
        .isEqualTo(XYM_ISO_BE);

    Point xyzm = (Point) codec.readText("POINT ZM (1 2 3 4)");
    assertThat(hex(codec.writeBinary(xyzm, WkbWriteOptions.iso(GeometryByteOrder.LITTLE_ENDIAN))))
        .isEqualTo(XYZM_ISO_LE);
    assertThat(
            hex(
                codec.writeBinary(
                    xyzm,
                    WkbWriteOptions.ewkb(GeometryByteOrder.BIG_ENDIAN, SridEncoding.ALWAYS))))
        .isEqualTo("00E000000100000000" + XYZM_EWKB_BE_SRID.substring(18));
  }

  @Test
  void writesStandardFieldBinaryWithKnownSrid() throws Exception {
    Point point = (Point) codec.readText("POINT (1 2)");
    point.setSRID(2056);
    assertThat(hex(codec.writeBinary(point, WkbWriteOptions.standardFieldBinary())))
        .isEqualTo("0020000001000008083FF00000000000004000000000000000");
  }

  @Test
  void readsIndependentCurveFixturesWithoutLosingControls() throws Exception {
    CircularString circular = (CircularString) codec.readBinary(hex(CIRCULAR_XY_LE));
    assertThat(circular.getControlPoints())
        .extracting(c -> c.x, c -> c.y)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1.0, 0.0),
            org.assertj.core.groups.Tuple.tuple(0.0, 1.0),
            org.assertj.core.groups.Tuple.tuple(-1.0, 0.0));

    CompoundCurve compound = (CompoundCurve) codec.readBinary(hex(COMPOUND_XY_LE));
    assertThat(compound.getComponents()).hasSize(2);
    assertThat(compound.getComponents().get(1)).isInstanceOf(CircularString.class);

    CurvePolygon curvePolygon = (CurvePolygon) codec.readBinary(hex(CURVEPOLYGON_XY_LE));
    assertThat(curvePolygon.getCurveRings()).hasSize(1);
    assertThat(curvePolygon.getCurveRings().get(0)).isInstanceOf(CircularString.class);

    MultiCurve multiCurve = (MultiCurve) codec.readBinary(hex(MULTICURVE_XY_LE));
    assertThat(multiCurve.getCurves()).hasSize(2);
    assertThat(multiCurve.getCurves().get(1)).isInstanceOf(CircularString.class);

    MultiSurface multiSurface = (MultiSurface) codec.readBinary(hex(MULTISURFACE_XY_LE));
    assertThat(multiSurface.getSurfaces()).hasSize(2);
    assertThat(multiSurface.getSurfaces().get(1)).isInstanceOf(CurvePolygon.class);
  }

  @Test
  void writesIndependentCurveFixturesExactly() throws Exception {
    Geometry circular = codec.readText("CIRCULARSTRING (1 0, 0 1, -1 0)");
    assertThat(hex(codec.writeBinary(circular, WkbWriteOptions.iso(GeometryByteOrder.LITTLE_ENDIAN))))
        .isEqualTo(CIRCULAR_XY_LE);

    Geometry compound =
        codec.readText("COMPOUNDCURVE ((0 0, 1 0), CIRCULARSTRING (1 0, 2 1, 3 0))");
    assertThat(hex(codec.writeBinary(compound, WkbWriteOptions.iso(GeometryByteOrder.LITTLE_ENDIAN))))
        .isEqualTo(COMPOUND_XY_LE);

    Geometry curvePolygon =
        codec.readText("CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0))");
    assertThat(
            hex(
                codec.writeBinary(
                    curvePolygon, WkbWriteOptions.iso(GeometryByteOrder.LITTLE_ENDIAN))))
        .isEqualTo(CURVEPOLYGON_XY_LE);
  }

  @Test
  void readsIsoDimensionOffsetsForCurveTypesInBothByteOrders() throws Exception {
    assertCurveLayout(CIRC_1_LE, CoordinateLayout.XYZ, 3);
    assertCurveLayout(CIRC_1_BE, CoordinateLayout.XYZ, 3);
    assertCurveLayout(CIRC_2_LE, CoordinateLayout.XYM, 10);
    assertCurveLayout(CIRC_2_BE, CoordinateLayout.XYM, 10);
    assertCurveLayout(CIRC_3_LE, CoordinateLayout.XYZM, 10);
    assertCurveLayout(CIRC_3_BE, CoordinateLayout.XYZM, 10);
  }

  @Test
  void readsCurvesInsideOrdinaryCollectionsAndMixedChildByteOrders() throws Exception {
    Geometry collection = codec.readBinary(hex(COLLECTION_WITH_CURVES));
    assertThat(collection).isInstanceOf(GeometryCollection.class);
    GeometryCollection geometryCollection = (GeometryCollection) collection;
    assertThat(geometryCollection.getNumGeometries()).isEqualTo(3);
    assertThat(geometryCollection.getGeometryN(0)).isInstanceOf(Point.class);
    assertThat(geometryCollection.getGeometryN(1)).isInstanceOf(CircularString.class);
    assertThat(geometryCollection.getGeometryN(2)).isInstanceOf(CurvePolygon.class);

    Geometry mixed = codec.readBinary(hex(MIXED_ORDER_COLLECTION));
    assertThat(mixed.getNumGeometries()).isEqualTo(2);
    assertThat(((Point) mixed.getGeometryN(0)).getX()).isEqualTo(1.0);
    assertThat(((Point) mixed.getGeometryN(1)).getX()).isEqualTo(3.0);
  }

  @Test
  void supportsAllLinearTypesAndEmptyPrimitives() throws Exception {
    for (String text :
        new String[] {
          "POINT (1 2)",
          "LINESTRING (0 0, 1 1)",
          "POLYGON ((0 0, 1 0, 1 1, 0 0))",
          "MULTIPOINT ((1 2), (3 4))",
          "MULTILINESTRING ((0 0, 1 1))",
          "MULTIPOLYGON (((0 0, 1 0, 1 1, 0 0)))",
          "GEOMETRYCOLLECTION (POINT (1 2), LINESTRING (0 0, 1 1))"
        }) {
      Geometry geometry = codec.readText(text);
      byte[] wkb = codec.writeBinary(geometry, WkbWriteOptions.standardFieldBinary());
      Geometry decoded = codec.readBinary(wkb);
      GeometryAssertions.assertGeometryPreserved(geometry, decoded);
    }
    for (String text : new String[] {
          "POINT EMPTY", "POINT Z EMPTY", "LINESTRING EMPTY", "LINESTRING M EMPTY", "POLYGON EMPTY"
        }) {
      Geometry geometry = codec.readText(text);
      Geometry decoded =
          codec.readBinary(codec.writeBinary(geometry, WkbWriteOptions.standardFieldBinary()));
      GeometryAssertions.assertGeometryPreserved(geometry, decoded);
    }
  }

  @Test
  void readBinaryRejectsEmptyAndNonWkbPayloads() throws Exception {
    assertCode(() -> codec.readBinary(new byte[0]), GeometryCodecErrorCode.GC_INVALID_WKB);
    assertCode(
        () -> codec.readBinary("WKT".getBytes(StandardCharsets.UTF_8)),
        GeometryCodecErrorCode.GC_INVALID_WKB);
    assertThat(codec.readBinary(null)).isNull();
  }

  @Test
  void writeBinaryRejectsIsoWithSridOption() throws Exception {
    Point point = (Point) codec.readText("POINT (1 2)");
    assertCode(
        () ->
            codec.writeBinary(
                point,
                new WkbWriteOptions(
                    WkbDialect.ISO_SQL_MM,
                    GeometryByteOrder.BIG_ENDIAN,
                    SridEncoding.IF_KNOWN)),
        GeometryCodecErrorCode.GC_TYPE_MISMATCH);
  }

  private void assertPoint(
      String hex,
      CoordinateLayout layout,
      double x,
      double y,
      double z,
      double m,
      int srid)
      throws Exception {
    Point point = (Point) codec.readBinary(HexFormat.of().parseHex(hex));
    var sequence = point.getCoordinateSequence();
    assertThat(sequence.getDimension()).isEqualTo(layout.dimension());
    assertThat(sequence.getMeasures()).isEqualTo(layout.measures());
    assertThat(sequence.getX(0)).isEqualTo(x);
    assertThat(sequence.getY(0)).isEqualTo(y);
    assertEquals(layout.hasZ() ? z : Double.NaN, sequence.getZ(0), "z");
    assertEquals(layout.hasM() ? m : Double.NaN, sequence.getM(0), "m");
    assertThat(point.getSRID()).isEqualTo(srid);
  }

  private void assertCurveLayout(String hex, CoordinateLayout layout, double firstM)
      throws Exception {
    CircularString curve = (CircularString) codec.readBinary(HexFormat.of().parseHex(hex));
    var sequence = curve.getControlPointSequence();
    assertThat(sequence.getDimension()).isEqualTo(layout.dimension());
    assertThat(sequence.getMeasures()).isEqualTo(layout.measures());
    if (layout.hasM()) {
      assertThat(sequence.getM(0)).isEqualTo(firstM);
    }
  }

  private static byte[] hex(String value) {
    return HexFormat.of().parseHex(value);
  }

  private static String hex(byte[] value) {
    return HexFormat.of().formatHex(value).toUpperCase();
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
