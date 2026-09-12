package com.atolcd.hop.gis.geometry.curve;

import org.locationtech.jts.geom.Coordinate;

public final class StraightSegment implements CurveSegment {
  private final Coordinate start;
  private final Coordinate end;

  public StraightSegment(Coordinate start, Coordinate end) {
    this.start = start.copy();
    this.end = end.copy();
  }

  @Override
  public Coordinate getStartPoint() {
    return start.copy();
  }

  @Override
  public Coordinate getEndPoint() {
    return end.copy();
  }

  @Override
  public Coordinate[] linearize(double maxError) {
    return new Coordinate[] {getStartPoint(), getEndPoint()};
  }
}
