package com.atolcd.hop.gis.geometry.curve;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;

/** Circular arc represented exactly by start, supporting (mid), and end point. */
public final class ArcSegment implements CurveSegment {
  private static final long SEGMENT_CAP = 1_000_000L;

  private final Coordinate start;
  private final Coordinate mid;
  private final Coordinate end;

  public ArcSegment(Coordinate start, Coordinate mid, Coordinate end) {
    this.start = start.copy();
    this.mid = mid.copy();
    this.end = end.copy();
  }

  @Override
  public Coordinate getStartPoint() {
    return start.copy();
  }

  public Coordinate getMidPoint() {
    return mid.copy();
  }

  @Override
  public Coordinate getEndPoint() {
    return end.copy();
  }

  @Override
  public Coordinate[] linearize(double maxError) {
    requirePositiveError(maxError);
    // Identical reverse arcs use exactly the same floating point operations.
    if (start.compareTo(end) > 0) {
      Coordinate[] reversed = new ArcSegment(end, mid, start).linearize(maxError);
      for (int i = 0, j = reversed.length - 1; i < j; i++, j--) {
        Coordinate p = reversed[i];
        reversed[i] = reversed[j];
        reversed[j] = p;
      }
      return reversed;
    }
    Plan plan = plan();
    if (plan.degenerate()) {
      return new Coordinate[] {getStartPoint(), getMidPoint(), getEndPoint()};
    }
    List<Coordinate> values = new ArrayList<>();
    append(values, plan, plan.angle, plan.midSweep, start, mid, maxError);
    values.remove(values.size() - 1);
    append(values, plan, plan.angle + plan.midSweep, plan.sweep - plan.midSweep, mid, end, maxError);
    return values.toArray(Coordinate[]::new);
  }

  /**
   * Number of coordinates {@link #linearize(double)} would create, without allocating a vertex
   * list. Returns {@code maximum + 1} as soon as the budget is exceeded.
   */
  public long linearizedCoordinateCount(double maxError, long maximum) {
    requirePositiveError(maxError);
    if (maximum < 0) {
      throw new IllegalArgumentException("Maximum must not be negative");
    }
    Plan plan = plan();
    if (plan.degenerate()) {
      return 3 <= maximum ? 3 : exceeded(maximum);
    }
    long first = segmentCount(plan, plan.midSweep, maxError);
    long second = segmentCount(plan, plan.sweep - plan.midSweep, maxError);
    long total;
    try {
      total = Math.addExact(Math.addExact(first, second), 1);
    } catch (ArithmeticException e) {
      return exceeded(maximum);
    }
    return total <= maximum ? total : exceeded(maximum);
  }

  public long linearizedCoordinateCount(double maxError) {
    return linearizedCoordinateCount(maxError, SEGMENT_CAP);
  }

  private Plan plan() {
    boolean full = start.equals2D(end) && !start.equals2D(mid);
    Circle circle =
        full
            ? new Circle((start.x + mid.x) / 2, (start.y + mid.y) / 2, start.distance(mid) / 2)
            : circleThrough(start, mid, end);
    if (circle == null || circle.radius == 0) {
      return Plan.degeneratePlan();
    }
    double a0 = Math.atan2(start.y - circle.cy, start.x - circle.cx);
    double am = Math.atan2(mid.y - circle.cy, mid.x - circle.cx);
    double a1 = Math.atan2(end.y - circle.cy, end.x - circle.cx);
    double sweep = full ? 2 * Math.PI : sweepThrough(a0, am, a1);
    double midSweep = full ? Math.PI : (sweep >= 0 ? positive(am - a0) : -positive(a0 - am));
    return new Plan(circle, a0, midSweep, sweep);
  }

  private static void append(
      List<Coordinate> result,
      Plan plan,
      double angle,
      double sweep,
      Coordinate a,
      Coordinate b,
      double error) {
    int n = segmentCount(plan, sweep, error);
    Circle circle = plan.circle;
    for (int i = 0; i <= n; i++) {
      if (i == 0) {
        result.add(a.copy());
        continue;
      }
      if (i == n) {
        result.add(b.copy());
        continue;
      }
      double t = (double) i / n, theta = angle + sweep * t;
      double z = interpolate(a.getZ(), b.getZ(), t), m = interpolate(a.getM(), b.getM(), t);
      double x = circle.cx + circle.radius * Math.cos(theta),
          y = circle.cy + circle.radius * Math.sin(theta);
      result.add(
          !Double.isNaN(m)
              ? (Double.isNaN(z)
                  ? new org.locationtech.jts.geom.CoordinateXYM(x, y, m)
                  : new org.locationtech.jts.geom.CoordinateXYZM(x, y, z, m))
              : new Coordinate(x, y, z));
    }
  }

  private static int segmentCount(Plan plan, double sweep, double error) {
    Circle circle = plan.circle;
    // asin form avoids cancellation when error/radius is small.
    double step =
        Math.min(Math.PI / 2, 4 * Math.asin(Math.sqrt(Math.min(1, error / (2 * circle.radius)))));
    double required = Math.ceil(Math.abs(sweep) / step);
    if (!Double.isFinite(required) || required > SEGMENT_CAP) {
      throw new IllegalArgumentException("Tolerance requires more than one million arc segments");
    }
    return Math.max(1, (int) required);
  }

  private static long exceeded(long maximum) {
    return maximum == Long.MAX_VALUE ? Long.MAX_VALUE : maximum + 1;
  }

  private static void requirePositiveError(double maxError) {
    if (!Double.isFinite(maxError) || maxError <= 0) {
      throw new IllegalArgumentException("Maximum deviation must be positive and finite");
    }
  }

  private static double interpolate(double a, double b, double t) {
    return Double.isNaN(a) || Double.isNaN(b) ? Double.NaN : a + (b - a) * t;
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
    double ux = b.x - a.x, uy = b.y - a.y, vx = c.x - a.x, vy = c.y - a.y;
    double d = 2 * (ux * vy - uy * vx);
    if (d == 0) return null;
    double u2 = ux * ux + uy * uy, v2 = vx * vx + vy * vy;
    double x = (u2 * vy - v2 * uy) / d, y = (v2 * ux - u2 * vx) / d;
    return new Circle(a.x + x, a.y + y, Math.hypot(x, y));
  }

  private record Circle(double cx, double cy, double radius) {}

  private record Plan(Circle circle, double angle, double midSweep, double sweep) {
    private static Plan degeneratePlan() {
      return new Plan(null, 0, 0, 0);
    }

    private boolean degenerate() {
      return circle == null;
    }
  }
}
