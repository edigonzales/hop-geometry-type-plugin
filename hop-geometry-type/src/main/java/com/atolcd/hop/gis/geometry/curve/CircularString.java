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
      throw new IllegalArgumentException(
          "CIRCULARSTRING requires an odd number of at least 3 points");
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
      throw new IllegalArgumentException(
          "CIRCULARSTRING requires an odd number of at least 3 points");
    }
    List<Coordinate> result = new ArrayList<>();
    for (int i = 0; i + 2 < points.length; i += 2) {
      Coordinate[] arc =
          new ArcSegment(points[i], points[i + 1], points[i + 2]).linearize(maxError);
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
    return Arrays.stream(coordinates).map(Coordinate::copy).toArray(Coordinate[]::new);
  }

  @Override
  protected CircularString copyInternal() {
    return new CircularString(controlPoints, getFactory());
  }

  @Override
  protected CircularString reverseInternal() {
    List<Coordinate> expanded = new ArrayList<>();
    expanded.add(controlPoints[0].copy());
    for (int k = 0; k + 2 < controlPoints.length; k += 2) {
      Coordinate a = controlPoints[k], m = controlPoints[k + 1], b = controlPoints[k + 2];
      if (a.equals2D(b)) {
        expanded.add(quarter(a, m));
        expanded.add(m.copy());
        expanded.add(quarter(m, b));
        expanded.add(b.copy());
      } else {
        expanded.add(m.copy());
        expanded.add(b.copy());
      }
    }
    Coordinate[] points = expanded.toArray(Coordinate[]::new);
    for (int i = 0, j = points.length - 1; i < j; i++, j--) {
      Coordinate p = points[i];
      points[i] = points[j];
      points[j] = p;
    }
    return new CircularString(points, getFactory());
  }

  private static Coordinate quarter(Coordinate a, Coordinate b) {
    Coordinate q = a.copy();
    double cx = a.x + (b.x - a.x) / 2, cy = a.y + (b.y - a.y) / 2;
    q.x = cx - (a.y - cy);
    q.y = cy + (a.x - cx);
    if (!Double.isNaN(a.getZ())) q.setZ((a.getZ() + b.getZ()) / 2);
    if (!Double.isNaN(a.getM())) q.setM((a.getM() + b.getM()) / 2);
    return q;
  }
}
