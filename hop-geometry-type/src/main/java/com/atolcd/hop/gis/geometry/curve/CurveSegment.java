package com.atolcd.hop.gis.geometry.curve;

import org.locationtech.jts.geom.Coordinate;

/** A true curve segment with an optional linearized JTS view. */
public interface CurveSegment {
  Coordinate getStartPoint();

  Coordinate getEndPoint();

  /**
   * Returns coordinates approximating the segment for the inherited JTS coordinate sequence.
   * The true segment representation is kept separately by the curve geometry classes.
   */
  Coordinate[] linearize(double maxError);
}
