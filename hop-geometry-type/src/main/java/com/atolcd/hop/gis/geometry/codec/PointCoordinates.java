package com.atolcd.hop.gis.geometry.codec;

import java.util.Objects;

/**
 * Values for one point. A {@code null} ordinate means "not supplied"; a NaN value means "declared
 * but missing". The layout is always part of the value.
 */
public record PointCoordinates(Double x, Double y, Double z, Double m, CoordinateLayout layout) {
  public PointCoordinates {
    Objects.requireNonNull(layout, "layout");
  }
}
