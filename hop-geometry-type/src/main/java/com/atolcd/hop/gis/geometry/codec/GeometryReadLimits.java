package com.atolcd.hop.gis.geometry.codec;

/**
 * Parser and materialization budgets for one input geometry.
 *
 * <p>These limits are protective decisions, not performance promises. They are applied before
 * arrays or curve objects are allocated.
 */
public record GeometryReadLimits(
    int maxDepth, long maxCoordinates, long maxComponents, long maxMaterializedCurveVertices) {
  public GeometryReadLimits {
    if (maxDepth < 1) {
      throw new IllegalArgumentException("maxDepth must be at least 1");
    }
    if (maxCoordinates < 0) {
      throw new IllegalArgumentException("maxCoordinates must not be negative");
    }
    if (maxComponents < 0) {
      throw new IllegalArgumentException("maxComponents must not be negative");
    }
    if (maxMaterializedCurveVertices < 0) {
      throw new IllegalArgumentException("maxMaterializedCurveVertices must not be negative");
    }
  }

  public static GeometryReadLimits defaults() {
    return new GeometryReadLimits(64, 10_000_000L, 1_000_000L, 5_000_000L);
  }

  public boolean allows(int depth) {
    return depth <= maxDepth;
  }
}
