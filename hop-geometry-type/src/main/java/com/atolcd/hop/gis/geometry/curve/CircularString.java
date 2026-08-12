package com.atolcd.hop.gis.geometry.curve;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/** SQL/MM CIRCULARSTRING represented as a JTS LineString plus exact control points. */
public final class CircularString extends LineString {
  public static final double DEFAULT_MAX_ERROR = 0.001;

  private final Coordinate[] controlPoints;

  public CircularString(Coordinate[] controlPoints, GeometryFactory factory) {
    super(
        factory
            .getCoordinateSequenceFactory()
            .create(linearizeControlPoints(controlPoints, DEFAULT_MAX_ERROR)),
        factory);
    if (controlPoints.length < 3 || controlPoints.length % 2 == 0) {
      throw new IllegalArgumentException("CIRCULARSTRING requires an odd number of at least 3 points");
    }
    this.controlPoints = copy(controlPoints);
  }

  public Coordinate[] getControlPoints() {
    return copy(controlPoints);
  }

  public List<ArcSegment> getArcSegments() {
    List<ArcSegment> arcs = new ArrayList<>((controlPoints.length - 1) / 2);
    for (int i = 0; i + 2 < controlPoints.length; i += 2) {
      arcs.add(new ArcSegment(controlPoints[i], controlPoints[i + 1], controlPoints[i + 2]));
    }
    return List.copyOf(arcs);
  }

  private static Coordinate[] linearizeControlPoints(Coordinate[] points, double maxError) {
    if (points.length < 3 || points.length % 2 == 0) {
      throw new IllegalArgumentException("CIRCULARSTRING requires an odd number of at least 3 points");
    }
    List<Coordinate> result = new ArrayList<>();
    for (int i = 0; i + 2 < points.length; i += 2) {
      Coordinate[] arc = new ArcSegment(points[i], points[i + 1], points[i + 2]).linearize(maxError);
      for (int j = 0; j < arc.length; j++) {
        if (!result.isEmpty() && j == 0) {
          continue;
        }
        result.add(arc[j]);
      }
    }
    return result.toArray(Coordinate[]::new);
  }

  private static Coordinate[] copy(Coordinate[] coordinates) {
    return Arrays.stream(coordinates).map(Coordinate::new).toArray(Coordinate[]::new);
  }
}
