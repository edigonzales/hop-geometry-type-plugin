package com.atolcd.hop.gis.geometry.curve;

import com.atolcd.hop.gis.geometry.codec.CoordinateLayout;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/** SQL/MM CIRCULARSTRING represented as a JTS LineString plus exact control points. */
public final class CircularString extends LineString {
  public static final double DEFAULT_MAX_ERROR = 0.001;

  private final Coordinate[] controlPoints;
  private final CoordinateSequence controlPointSequence;
  private final CoordinateLayout layout;

  /**
   * Legacy array constructor. Its declared layout follows the historical interpretation: a Z
   * ordinate exists only if at least one Z value is finite; an M ordinate exists if a coordinate
   * carries measure information.
   */
  public CircularString(Coordinate[] controlPoints, GeometryFactory factory) {
    this(legacySequence(controlPoints, factory), factory);
  }

  /**
   * Layout-preserving constructor used by the shared parsers. The sequence's declared dimension
   * and measures are retained, including all-NaN Z/M ordinates.
   */
  public CircularString(CoordinateSequence controls, GeometryFactory factory) {
    this(requireControls(controls), factory, true);
  }

  private CircularString(
      CoordinateSequence controlPointSequence, GeometryFactory factory, boolean ignored) {
    super(
        factory
            .getCoordinateSequenceFactory()
            .create(
                linearizeControlPoints(
                    controlPointSequence.toCoordinateArray(), DEFAULT_MAX_ERROR)),
        factory);
    this.layout = layoutOf(controlPointSequence);
    this.controlPointSequence = controlPointSequence.copy();
    this.controlPoints = copy(controlPointSequence.toCoordinateArray());
  }

  private static CoordinateSequence requireControls(CoordinateSequence controls) {
    if (controls == null || controls.size() < 3 || controls.size() % 2 == 0) {
      throw new IllegalArgumentException(
          "CIRCULARSTRING requires an odd number of at least 3 points");
    }
    return controls;
  }

  public Coordinate[] getControlPoints() {
    return copy(controlPoints);
  }

  /** Independent copy of the exact control-point sequence, including declared layout. */
  public CoordinateSequence getControlPointSequence() {
    return controlPointSequence.copy();
  }

  public CoordinateLayout getControlLayout() {
    return layout;
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

  private static CoordinateLayout layoutOf(CoordinateSequence sequence) {
    try {
      return CoordinateLayout.ofSequence(sequence.getDimension(), sequence.getMeasures());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "Unsupported CIRCULARSTRING control layout: dimension="
              + sequence.getDimension()
              + ", measures="
              + sequence.getMeasures(),
          e);
    }
  }

  private static CoordinateSequence legacySequence(
      Coordinate[] controlPoints, GeometryFactory factory) {
    if (controlPoints == null || controlPoints.length < 3 || controlPoints.length % 2 == 0) {
      throw new IllegalArgumentException(
          "CIRCULARSTRING requires an odd number of at least 3 points");
    }
    boolean hasZ = Arrays.stream(controlPoints).anyMatch(c -> !Double.isNaN(c.getZ()));
    boolean hasM =
        Arrays.stream(controlPoints)
            .anyMatch(
                c ->
                    c instanceof org.locationtech.jts.geom.CoordinateXYM
                        || c instanceof org.locationtech.jts.geom.CoordinateXYZM);
    return sequenceFor(controlPoints, CoordinateLayout.of(hasZ, hasM), factory);
  }

  private static CoordinateSequence sequenceFor(
      Coordinate[] points, CoordinateLayout layout, GeometryFactory factory) {
    CoordinateSequence sequence =
        factory
            .getCoordinateSequenceFactory()
            .create(points.length, layout.dimension(), layout.measures());
    for (int i = 0; i < points.length; i++) {
      Coordinate point = points[i];
      sequence.setOrdinate(i, CoordinateSequence.X, point.x);
      sequence.setOrdinate(i, CoordinateSequence.Y, point.y);
      if (layout.hasZ()) {
        sequence.setOrdinate(i, layout.zIndex(), point.getZ());
      }
      if (layout.hasM()) {
        sequence.setOrdinate(i, layout.mIndex(), point.getM());
      }
    }
    return sequence;
  }

  @Override
  protected CircularString copyInternal() {
    return new CircularString(controlPointSequence.copy(), getFactory());
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
    return new CircularString(sequenceFor(points, layout, getFactory()), getFactory());
  }

  private static Coordinate quarter(Coordinate a, Coordinate b) {
    Coordinate q = a.copy();
    double cx = a.x + (b.x - a.x) / 2, cy = a.y + (b.y - a.y) / 2;
    q.x = cx - (a.y - cy);
    q.y = cy + (a.x - cx);
    if (!Double.isNaN(a.getZ()) && !Double.isNaN(b.getZ())) q.setZ((a.getZ() + b.getZ()) / 2);
    if (!Double.isNaN(a.getM()) && !Double.isNaN(b.getM())) q.setM((a.getM() + b.getM()) / 2);
    return q;
  }
}
