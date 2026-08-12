package com.atolcd.hop.gis.geometry.curve;

import org.locationtech.jts.geom.Coordinate;

public final class StraightSegment implements CurveSegment {
  private final Coordinate start;
  private final Coordinate end;

  public StraightSegment(Coordinate start, Coordinate end) {
    this.start = new Coordinate(start);
    this.end = new Coordinate(end);
  }

  @Override
  public Coordinate getStartPoint() {
    return new Coordinate(start);
  }

  @Override
  public Coordinate getEndPoint() {
    return new Coordinate(end);
  }

  @Override
  public Coordinate[] linearize(double maxError) {
    return new Coordinate[] {getStartPoint(), getEndPoint()};
  }
}
