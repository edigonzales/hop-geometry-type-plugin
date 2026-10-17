package com.atolcd.hop.gis.geometry.codec;

import java.util.HexFormat;

/** Strict hex encoding/decoding. This class never parses or writes a geometry by itself. */
public final class GeometryHexCodec {
  private static final HexFormat UPPER = HexFormat.of().withUpperCase();

  private GeometryHexCodec() {}

  public static byte[] decode(String text) throws GeometryCodecException {
    if (text == null) {
      return null;
    }
    String value = text.strip();
    if (value.isEmpty()) {
      return null;
    }
    String prefix = prefixOf(value);
    String digits = prefix == null ? value : value.substring(prefix.length());
    if (digits.isEmpty()) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_INVALID_HEX, "Hex prefix without payload");
    }
    if ((digits.length() & 1) != 0) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_INVALID_HEX, "Hex payload has an odd number of digits");
    }
    for (int i = 0; i < digits.length(); i++) {
      if (!isAsciiHex(digits.charAt(i))) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_INVALID_HEX,
            "Invalid hex character at index " + i,
            i);
      }
    }
    long byteLength = digits.length() / 2L;
    if (byteLength > Integer.MAX_VALUE - 8L) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_INVALID_HEX, "Hex payload is too large");
    }
    byte[] result = new byte[(int) byteLength];
    for (int i = 0; i < result.length; i++) {
      result[i] = (byte) ((asciiValue(digits.charAt(i * 2)) << 4) | asciiValue(digits.charAt(i * 2 + 1)));
    }
    return result;
  }

  public static String encode(byte[] bytes) {
    return bytes == null ? null : UPPER.formatHex(bytes);
  }

  /** True if the trimmed text starts with exactly one supported explicit hex prefix. */
  public static boolean hasExplicitPrefix(String text) {
    if (text == null) {
      return false;
    }
    return prefixOf(text.strip()) != null;
  }

  /**
   * Implicit String→Geometry detection. Without a prefix only a plausible WKB header with ASCII
   * hex digits and even length is accepted. This is a heuristic gate, not a geometry parser.
   */
  public static boolean looksLikeHeaderedHex(String text) {
    if (text == null) {
      return false;
    }
    String value = text.strip();
    String prefix = prefixOf(value);
    if (prefix != null) {
      return true;
    }
    if (value.length() < 10 || (value.length() & 1) != 0) {
      return false;
    }
    if (!value.startsWith("00") && !value.startsWith("01")) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (!isAsciiHex(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static String prefixOf(String value) {
    if (value.startsWith("0x") || value.startsWith("0X")) {
      return value.substring(0, 2);
    }
    if (value.startsWith("\\x") || value.startsWith("\\X")) {
      return value.substring(0, 2);
    }
    return null;
  }

  private static boolean isAsciiHex(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static int asciiValue(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
      return c - 'A' + 10;
    }
    throw new IllegalArgumentException("Not an ASCII hex digit: " + c);
  }
}
