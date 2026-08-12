package com.atolcd.hop.gis.geometry.curve;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;

/** Circular arc represented exactly by start, supporting (mid), and end point. */
public final class ArcSegment implements CurveSegment {
  private static final double DEFAULT_MAX_ERROR = 0.001;

  private final Coordinate start;
  private final Coordinate mid;
  private final Coordinate end;

  public ArcSegment(Coordinate start, Coordinate mid, Coordinate end) {
    this.start = new Coordinate(start);
    this.mid = new Coordinate(mid);
    this.end = new Coordinate(end);
  }

  @Override
  public Coordinate getStartPoint() {
    return new Coordinate(start);
  }

  public Coordinate getMidPoint() {
    return new Coordinate(mid);
  }

  @Override
  public Coordinate getEndPoint() {
    return new Coordinate(end);
  }

  @Override
  public Coordinate[] linearize(double maxError) {
    double tolerance = maxError > 0 ? maxError : DEFAULT_MAX_ERROR;

    if (start.equals2D(end) && !start.equals2D(mid)) {
      return linearizeFullCircle(tolerance);
    }

    Circle circle = circleThrough(start, mid, end);
    if (circle == null) {
      return new Coordinate[] {getStartPoint(), getMidPoint(), getEndPoint()};
    }

    double a0 = Math.atan2(start.y - circle.cy, start.x - circle.cx);
    double am = Math.atan2(mid.y - circle.cy, mid.x - circle.cx);
    double a1 = Math.atan2(end.y - circle.cy, end.x - circle.cx);
    double sweep = sweepThrough(a0, am, a1);
    int steps = stepsFor(circle.radius, Math.abs(sweep), tolerance, 2);
    List<Coordinate> coordinates = new ArrayList<>(steps + 1);
    for (int i = 0; i <= steps; i++) {
      double angle = a0 + sweep * i / steps;
      coordinates.add(
          new Coordinate(
              circle.cx + circle.radius * Math.cos(angle),
              circle.cy + circle.radius * Math.sin(angle)));
    }
    coordinates.set(0, getStartPoint());
    coordinates.set(coordinates.size() - 1, getEndPoint());
    return coordinates.toArray(Coordinate[]::new);
  }

  private Coordinate[] linearizeFullCircle(double tolerance) {
    double cx = (start.x + mid.x) / 2.0;
    double cy = (start.y + mid.y) / 2.0;
    double radius = Math.hypot(start.x - mid.x, start.y - mid.y) / 2.0;
    if (radius < 1e-14) {
      return new Coordinate[] {getStartPoint(), getMidPoint(), getEndPoint()};
    }

    double startAngle = Math.atan2(start.y - cy, start.x - cx);
    int steps = stepsFor(radius, 2.0 * Math.PI, tolerance, 4);
    if ((steps & 1) != 0) {
      steps++;
    }

    Coordinate[] coordinates = new Coordinate[steps + 1];
    for (int i = 0; i <= steps; i++) {
      double angle = startAngle + 2.0 * Math.PI * i / steps;
      coordinates[i] =
          new Coordinate(cx + radius * Math.cos(angle), cy + radius * Math.sin(angle));
    }
    coordinates[0] = getStartPoint();
    coordinates[steps / 2] = getMidPoint();
    coordinates[steps] = getEndPoint();
    return coordinates;
  }

  private static int stepsFor(double radius, double sweep, double tolerance, int minimum) {
    double maxStep;
    if (tolerance >= radius) {
      maxStep = Math.PI / 2.0;
    } else {
      maxStep = 2.0 * Math.acos(1.0 - tolerance / radius);
    }
    if (!Double.isFinite(maxStep) || maxStep <= 0.0) {
      maxStep = Math.PI / 180.0;
    }
    return Math.max(minimum, (int) Math.ceil(sweep / maxStep));
  }

  private static double sweepThrough(double start, double mid, double end) {
    double ccwEnd = positive(end - start);
    double ccwMid = positive(mid - start);
    if (ccwMid <= ccwEnd) {
      return ccwEnd;
    }
    return ccwEnd - 2.0 * Math.PI;
  }

  private static double positive(double angle) {
    double value = angle % (2.0 * Math.PI);
    return value < 0 ? value + 2.0 * Math.PI : value;
  }

  private static Circle circleThrough(Coordinate a, Coordinate b, Coordinate c) {
    double d =
        2.0
            * (a.x * (b.y - c.y)
                + b.x * (c.y - a.y)
                + c.x * (a.y - b.y));
    if (Math.abs(d) < 1e-14) {
      return null;
    }
    double a2 = a.x * a.x + a.y * a.y;
    double b2 = b.x * b.x + b.y * b.y;
    double c2 = c.x * c.x + c.y * c.y;
    double cx = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d;
    double cy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d;
    return new Circle(cx, cy, Math.hypot(a.x - cx, a.y - cy));
  }

  private record Circle(double cx, double cy, double radius) {}
}
