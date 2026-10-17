package com.atolcd.hop.gis.geometry.codec;

/** Declared coordinate layout of a primitive geometry or curve control sequence. */
public enum CoordinateLayout {
  XY(false, false),
  XYZ(true, false),
  XYM(false, true),
  XYZM(true, true);

  private final boolean hasZ;
  private final boolean hasM;

  CoordinateLayout(boolean hasZ, boolean hasM) {
    this.hasZ = hasZ;
    this.hasM = hasM;
  }

  public boolean hasZ() {
    return hasZ;
  }

  public boolean hasM() {
    return hasM;
  }

  /** Number of declared ordinates: 2, 3 or 4. */
  public int dimension() {
    return 2 + (hasZ ? 1 : 0) + (hasM ? 1 : 0);
  }

  /** Number of declared measures: 0 or 1. */
  public int measures() {
    return hasM ? 1 : 0;
  }

  /** Z index in a coordinate sequence, or -1. */
  public int zIndex() {
    return hasZ ? 2 : -1;
  }

  /** M index in a coordinate sequence, or -1. */
  public int mIndex() {
    if (!hasM) {
      return -1;
    }
    return hasZ ? 3 : 2;
  }

  /** SQL/MM type offset: 0, 1000, 2000 or 3000. */
  public int isoOffset() {
    if (hasZ && hasM) {
      return 3000;
    }
    if (hasZ) {
      return 1000;
    }
    if (hasM) {
      return 2000;
    }
    return 0;
  }

  /** EWKB Z/M flags (without the SRID flag). */
  public int ewkbFlags() {
    int flags = 0;
    if (hasZ) {
      flags |= 0x80000000;
    }
    if (hasM) {
      flags |= 0x40000000;
    }
    return flags;
  }

  public static CoordinateLayout of(boolean hasZ, boolean hasM) {
    if (hasZ && hasM) {
      return XYZM;
    }
    if (hasZ) {
      return XYZ;
    }
    if (hasM) {
      return XYM;
    }
    return XY;
  }

  /** Layout for a valid JTS sequence dimension/measures pair. */
  public static CoordinateLayout ofSequence(int dimension, int measures) {
    if (dimension == 2 && measures == 0) {
      return XY;
    }
    if (dimension == 3 && measures == 0) {
      return XYZ;
    }
    if (dimension == 3 && measures == 1) {
      return XYM;
    }
    if (dimension == 4 && measures == 1) {
      return XYZM;
    }
    throw new IllegalArgumentException(
        "Unsupported coordinate sequence layout: dimension="
            + dimension
            + ", measures="
            + measures);
  }
}
