package com.atolcd.hop.gis.geometry.codec;

import java.util.Objects;

/** Immutable explicit WKB write options. */
public record WkbWriteOptions(
    WkbDialect dialect, GeometryByteOrder byteOrder, SridEncoding sridEncoding) {
  public WkbWriteOptions {
    Objects.requireNonNull(dialect, "dialect");
    Objects.requireNonNull(byteOrder, "byteOrder");
    Objects.requireNonNull(sridEncoding, "sridEncoding");
  }

  /** EWKB, big endian, known SRIDs only. This is the standard Hop field-binary output. */
  public static WkbWriteOptions standardFieldBinary() {
    return new WkbWriteOptions(WkbDialect.EWKB, GeometryByteOrder.BIG_ENDIAN, SridEncoding.IF_KNOWN);
  }

  /** ISO/SQL-MM WKB without SRID. */
  public static WkbWriteOptions iso(GeometryByteOrder order) {
    return new WkbWriteOptions(WkbDialect.ISO_SQL_MM, order, SridEncoding.OMIT);
  }

  /** EWKB with an explicit root SRID flag, including SRID 0. */
  public static WkbWriteOptions ewkb(GeometryByteOrder order) {
    return new WkbWriteOptions(WkbDialect.EWKB, order, SridEncoding.ALWAYS);
  }

  public static WkbWriteOptions ewkb(GeometryByteOrder order, SridEncoding sridEncoding) {
    return new WkbWriteOptions(WkbDialect.EWKB, order, sridEncoding);
  }

  public void validate() {
    if (dialect == WkbDialect.ISO_SQL_MM && sridEncoding != SridEncoding.OMIT) {
      throw new IllegalArgumentException("ISO/SQL-MM WKB cannot include an SRID");
    }
  }
}
