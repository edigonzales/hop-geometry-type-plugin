package com.atolcd.hop.gis.geometry.codec;

import java.nio.ByteOrder;

/** Wire byte order, named after its WKB marker semantics. */
public enum GeometryByteOrder {
  BIG_ENDIAN(ByteOrder.BIG_ENDIAN),
  LITTLE_ENDIAN(ByteOrder.LITTLE_ENDIAN);

  private final ByteOrder byteOrder;

  GeometryByteOrder(ByteOrder byteOrder) {
    this.byteOrder = byteOrder;
  }

  public ByteOrder toByteOrder() {
    return byteOrder;
  }

  public static GeometryByteOrder fromByteOrder(ByteOrder byteOrder) {
    return byteOrder == ByteOrder.LITTLE_ENDIAN ? LITTLE_ENDIAN : BIG_ENDIAN;
  }
}
