package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;

class GeometryCodecMalformedInputTest {
  private static final String VALID_CURVE =
      "010800000003000000000000000000F03F00000000000000000000000000000000000000000000F03F000000000000F0BF0000000000000000";
  private static final String VALID_POINT =
      "0101000000000000000000F03F0000000000000040";

  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());

  @Test
  void rejectsEveryTruncationOfValidBinaryFixtures() {
    for (String fixture : new String[] {VALID_POINT, VALID_CURVE}) {
      byte[] bytes = HexFormat.of().parseHex(fixture);
      for (int length = 0; length < bytes.length; length++) {
        byte[] truncated = java.util.Arrays.copyOf(bytes, length);
        assertThatThrownBy(() -> codec.readBinary(truncated))
            .as("truncation at byte " + length)
            .isInstanceOf(GeometryCodecException.class);
      }
    }
  }

  @Test
  void rejectsEveryTruncationOfValidText() {
    for (String fixture :
        new String[] {
          "POINT (1 2)",
          "CIRCULARSTRING (1 0, 0 1, -1 0)",
          "GEOMETRYCOLLECTION (POINT (1 2), CIRCULARSTRING (1 0, 0 1, -1 0))"
        }) {
      for (int length = 1; length < fixture.length(); length++) {
        String truncated = fixture.substring(0, length);
        assertThatThrownBy(() -> codec.readText(truncated))
            .as(truncated)
            .isInstanceOf(GeometryCodecException.class);
      }
    }
  }

  @Test
  void rejectsHugeAndNegativeCountsBeforeAllocation() {
    byte[] hugeLine =
        ByteBuffer.allocate(1 + 4 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put((byte) 1)
            .putInt(2)
            .putInt(0xFFFFFFFF)
            .array();
    assertCode(() -> codec.readBinary(hugeLine), GeometryCodecErrorCode.GC_LIMIT_EXCEEDED);

    byte[] hugeCollection =
        ByteBuffer.allocate(1 + 4 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put((byte) 1)
            .putInt(7)
            .putInt(Integer.MAX_VALUE)
            .array();
    assertCode(() -> codec.readBinary(hugeCollection), GeometryCodecErrorCode.GC_LIMIT_EXCEEDED);
  }

  @Test
  void enforcesNestingDepth64AndRejects65() throws Exception {
    Geometry allowed = nestedCollections(63);
    byte[] allowedBytes =
        codec.writeBinary(allowed, WkbWriteOptions.standardFieldBinary());
    Geometry read = codec.readBinary(allowedBytes);
    GeometryAssertions.assertGeometryPreserved(allowed, read);

    Geometry tooDeep = nestedCollections(64);
    byte[] tooDeepBytes =
        codec.writeBinary(tooDeep, WkbWriteOptions.standardFieldBinary());
    assertCode(() -> codec.readBinary(tooDeepBytes), GeometryCodecErrorCode.GC_LIMIT_EXCEEDED);
  }

  @Test
  void enforcesCurveMaterializationBudgetBeforeAllocation() {
    GeometryCodec limited =
        new GeometryCodec(new GeometryReadLimits(64, 1000, 1000, 10));
    assertCode(
        () -> limited.readText("CIRCULARSTRING (1 0, 0 1, -1 0)"),
        GeometryCodecErrorCode.GC_LIMIT_EXCEEDED);
  }

  @Test
  void rejectsStructurallyWrongCollectionMembers() {
    byte[] multiPointWithLine =
        ByteBuffer.allocate(9 + 1 + 4 + 4 + 4 * 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put((byte) 1)
            .putInt(4)
            .putInt(1)
            .put((byte) 1)
            .putInt(2)
            .putInt(2)
            .putDouble(0)
            .putDouble(0)
            .putDouble(1)
            .putDouble(1)
            .array();
    assertCode(
        () -> codec.readBinary(multiPointWithLine), GeometryCodecErrorCode.GC_INVALID_WKB);

    byte[] compoundWithPoint =
        ByteBuffer.allocate(1 + 4 + 4 + 1 + 4 + 2 * 8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put((byte) 1)
            .putInt(9)
            .putInt(1)
            .put((byte) 1)
            .putInt(1)
            .putDouble(1)
            .putDouble(2)
            .array();
    assertCode(
        () -> codec.readBinary(compoundWithPoint), GeometryCodecErrorCode.GC_INVALID_WKB);
  }

  @Test
  void rejectsInvalidHeadersAndStructuralPayloads() {
    assertCode(
        () -> codec.readBinary(HexFormat.of().parseHex("0200000001")),
        GeometryCodecErrorCode.GC_INVALID_WKB);
    assertCode(
        () -> codec.readBinary(HexFormat.of().parseHex("00100000013FF00000000000004000000000000000")),
        GeometryCodecErrorCode.GC_INVALID_WKB);
    assertCode(
        () -> codec.readBinary(HexFormat.of().parseHex("002003E93FF00000000000004000000000000000")),
        GeometryCodecErrorCode.GC_INVALID_WKB);
    assertCode(
        () -> codec.readBinary(HexFormat.of().parseHex("00000000633FF00000000000004000000000000000")),
        GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY);
    assertCode(
        () -> codec.readBinary(HexFormat.of().parseHex("0020000001FFFFFFFF3FF00000000000004000000000000000")),
        GeometryCodecErrorCode.GC_INVALID_SRID);
    assertCode(
        () -> codec.readBinary(HexFormat.of().parseHex("0101000000000000000000F03F000000000000004000")),
        GeometryCodecErrorCode.GC_INVALID_WKB);
  }

  @Test
  void rejectsOpenRingsShortLinesEmptyCurvesAndDimensionedEmptyMulti() {
    // Polygon LE, one ring with four unclosed points (0 0), (1 0), (1 1), (2 2).
    String openRing =
        "0103000000010000000400000000000000000000000000000000000000000000000000F03F0000000000000000000000000000F03F000000000000F03F00000000000000400000000000000040000000000000";
    assertCode(() -> codec.readBinary(HexFormat.of().parseHex(openRing)), GeometryCodecErrorCode.GC_INVALID_WKB);

    // LineString LE with exactly one coordinate.
    String shortLine = "01020000000100000000000000000000000000000000000000";
    assertCode(() -> codec.readBinary(HexFormat.of().parseHex(shortLine)), GeometryCodecErrorCode.GC_INVALID_WKB);

    // CircularString LE with zero controls.
    String emptyCurve = "010800000000000000";
    assertCode(() -> codec.readBinary(HexFormat.of().parseHex(emptyCurve)), GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE);

    // MultiPoint Z with zero members.
    String emptyMultiZ = "01EC03000000000000";
    assertCode(() -> codec.readBinary(HexFormat.of().parseHex(emptyMultiZ)), GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT);
  }

  @Test
  void rejectsMixedPositiveSridsInBinaryTree() {
    assertCode(
        () ->
            codec.readBinary(
                HexFormat.of()
                    .parseHex(
                        "010700000002000000010100002008080000000000000000F03F00000000000000400101000020E6100000000000000000F03F0000000000000040")),
        GeometryCodecErrorCode.GC_MIXED_SRID);
    assertCode(
        () ->
            codec.readBinary(
                HexFormat.of()
                    .parseHex(
                        "010700002008080000010000000101000020E6100000000000000000F03F0000000000000040")),
        GeometryCodecErrorCode.GC_MIXED_SRID);
  }

  @Test
  void rejectsNonFiniteCoordinatesAndMalformedHex() {
    assertCode(
        () -> codec.readBinary(HexFormat.of().parseHex("0101000000000000000000F07F0000000000000040")),
        GeometryCodecErrorCode.GC_INVALID_WKB);
    assertCode(() -> codec.readHex("0x0"), GeometryCodecErrorCode.GC_INVALID_HEX);
    assertCode(
        () -> codec.readBinary("not wkb".getBytes(StandardCharsets.UTF_8)),
        GeometryCodecErrorCode.GC_INVALID_WKB);
  }

  @Test
  void rejectsCommentsAndTrailingJunkInText() {
    assertCode(
        () -> codec.readText("POINT (1 2) // comment"), GeometryCodecErrorCode.GC_INVALID_WKT);
    assertCode(
        () -> codec.readText("MULTISURFACE (BOGUS)"), GeometryCodecErrorCode.GC_INVALID_WKT);
  }

  private static Geometry nestedCollections(int wrappers) {
    GeometryFactory factory = new GeometryFactory();
    Geometry current = factory.createPoint(new Coordinate(1, 2));
    for (int i = 0; i < wrappers; i++) {
      current = factory.createGeometryCollection(new Geometry[] {current});
    }
    return current;
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
