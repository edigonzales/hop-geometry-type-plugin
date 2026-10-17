package com.atolcd.hop.gis.geometry.codec;

import java.util.Objects;

/**
 * Checked geometry codec failure with a stable code and a deliberately short message. The offset
 * is a character offset for text input and a byte offset for binary input; -1 means unknown.
 */
public final class GeometryCodecException extends Exception {
  private static final long serialVersionUID = 1L;

  private final GeometryCodecErrorCode code;
  private final int offset;

  public GeometryCodecException(
      GeometryCodecErrorCode code, String safeMessage, int offset, Throwable cause) {
    super(format(code, safeMessage, offset), cause);
    this.code = Objects.requireNonNull(code, "code");
    this.offset = offset;
  }

  public GeometryCodecException(GeometryCodecErrorCode code, String safeMessage) {
    this(code, safeMessage, -1, null);
  }

  public GeometryCodecException(GeometryCodecErrorCode code, String safeMessage, int offset) {
    this(code, safeMessage, offset, null);
  }

  public GeometryCodecErrorCode code() {
    return code;
  }

  /** Character offset for text input, byte offset for binary input, or -1. */
  public int offset() {
    return offset;
  }

  private static String format(
      GeometryCodecErrorCode code, String safeMessage, int offset) {
    String message = safeMessage == null || safeMessage.isBlank() ? code.name() : safeMessage;
    if (message.length() > 500) {
      message = message.substring(0, 500) + "…";
    }
    StringBuilder result = new StringBuilder(code.name());
    if (offset >= 0) {
      result.append(" at offset ").append(offset);
    }
    if (!message.isBlank()) {
      result.append(": ").append(message);
    }
    return result.toString();
  }
}
