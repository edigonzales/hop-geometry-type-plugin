package com.atolcd.hop.gis.geometry.codec;

/** Stable, user-facing geometry codec error codes. */
public enum GeometryCodecErrorCode {
  GC_INVALID_WKT,
  GC_INVALID_WKB,
  GC_INVALID_HEX,
  GC_INVALID_SRID,
  GC_MIXED_SRID,
  GC_UNSUPPORTED_GEOMETRY,
  GC_UNSUPPORTED_LAYOUT,
  GC_UNSUPPORTED_EMPTY_CURVE,
  GC_NONFINITE_COORDINATE,
  GC_LIMIT_EXCEEDED,
  GC_NOT_A_POINT,
  GC_PARTIAL_COORDINATE_NULL,
  GC_TYPE_MISMATCH
}
