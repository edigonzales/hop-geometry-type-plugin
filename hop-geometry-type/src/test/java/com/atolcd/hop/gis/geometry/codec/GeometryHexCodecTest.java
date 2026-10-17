package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HexFormat;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;

class GeometryHexCodecTest {
  private static final String POINT_HEX =
      "0101000000000000000000F03F0000000000000040";
  private static final byte[] POINT_BYTES = HexFormat.of().parseHex(POINT_HEX);
  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());

  @Test
  void decodesUpperLowerAndAllFourPrefixes() throws Exception {
    for (String text :
        new String[] {
          POINT_HEX,
          POINT_HEX.toLowerCase(),
          "0x" + POINT_HEX,
          "0X" + POINT_HEX,
          "\\x" + POINT_HEX,
          "\\X" + POINT_HEX,
          "  0x" + POINT_HEX + "  "
        }) {
      assertThat(GeometryHexCodec.decode(text)).as(text).isEqualTo(POINT_BYTES);
    }
  }

  @Test
  void encodesUppercaseWithoutPrefixAndNullAsNull() {
    assertThat(GeometryHexCodec.encode(POINT_BYTES)).isEqualTo(POINT_HEX);
    assertThat(GeometryHexCodec.encode(null)).isNull();
  }

  @Test
  void rejectsMalformedHexBeforeParsingGeometry() {
    for (String text :
        new String[] {
          "0x",
          "\\x",
          "0x0",
          "0x0G",
          "0x01 01",
          "0x" + POINT_HEX + "F",
          "0x" + POINT_HEX + "\uFF10"
        }) {
      assertCode(() -> GeometryHexCodec.decode(text), GeometryCodecErrorCode.GC_INVALID_HEX);
    }
  }

  @Test
  void detectsPrefixesAndPlausibleHeaderedHex() {
    assertThat(GeometryHexCodec.hasExplicitPrefix("0x" + POINT_HEX)).isTrue();
    assertThat(GeometryHexCodec.hasExplicitPrefix("\\x" + POINT_HEX)).isTrue();
    assertThat(GeometryHexCodec.hasExplicitPrefix(POINT_HEX)).isFalse();
    assertThat(GeometryHexCodec.looksLikeHeaderedHex(POINT_HEX)).isTrue();
    assertThat(GeometryHexCodec.looksLikeHeaderedHex(POINT_HEX.toLowerCase())).isTrue();
    assertThat(GeometryHexCodec.looksLikeHeaderedHex("0x" + POINT_HEX)).isTrue();
    assertThat(GeometryHexCodec.looksLikeHeaderedHex("POINT (1 2)")).isFalse();
    assertThat(GeometryHexCodec.looksLikeHeaderedHex("02" + POINT_HEX.substring(2))).isFalse();
    assertThat(GeometryHexCodec.looksLikeHeaderedHex("00")).isFalse();
    assertThat(GeometryHexCodec.looksLikeHeaderedHex(null)).isFalse();
  }

  @Test
  void readHexHandlesNullBlankAndGeometryErrors() throws Exception {
    assertThat(codec.readHex(null)).isNull();
    assertThat(codec.readHex("  ")).isNull();
    assertThat(codec.readHex(POINT_HEX).getGeometryType()).isEqualTo("Point");
    assertCode(() -> codec.readHex("0x"), GeometryCodecErrorCode.GC_INVALID_HEX);
    assertCode(
        () -> codec.readHex("0x0101000000"), GeometryCodecErrorCode.GC_INVALID_WKB);
  }

  @Test
  void explicitWktModeDoesNotSilentlyParseHex() {
    assertThatThrownBy(() -> codec.readText(POINT_HEX))
        .isInstanceOf(GeometryCodecException.class);
  }

  @Test
  void writeHexIsBinaryEncodingOnly() throws Exception {
    Geometry point = codec.readText("POINT (1 2)");
    point.setSRID(2056);
    String hex = codec.writeHex(point, WkbWriteOptions.standardFieldBinary());
    assertThat(hex).isEqualTo("0020000001000008083FF00000000000004000000000000000");
    Geometry decoded = codec.readHex(hex);
    assertThat(decoded.getSRID()).isEqualTo(2056);
    assertThat(codec.writeHex(null, WkbWriteOptions.standardFieldBinary())).isNull();
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
