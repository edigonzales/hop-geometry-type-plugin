package com.atolcd.hop.gis.geometry.codec;

import java.util.Objects;

/** Immutable explicit WKT/EWKT write options. */
public record WktWriteOptions(SridEncoding sridEncoding) {
  public WktWriteOptions {
    Objects.requireNonNull(sridEncoding, "sridEncoding");
  }

  public static WktWriteOptions wkt() {
    return new WktWriteOptions(SridEncoding.OMIT);
  }

  public static WktWriteOptions ewkt() {
    return new WktWriteOptions(SridEncoding.ALWAYS);
  }

  public static WktWriteOptions legacy() {
    return new WktWriteOptions(SridEncoding.IF_KNOWN);
  }
}
