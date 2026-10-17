package com.atolcd.hop.gis.geometry.codec;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CompoundCurve;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import com.atolcd.hop.gis.geometry.curve.MultiCurve;
import com.atolcd.hop.gis.geometry.curve.MultiSurface;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.impl.CoordinateArraySequence;

/** Declared XY/Z/M layouts and representability checks for the shared geometry model. */
public final class GeometryLayoutSupport {
  private GeometryLayoutSupport() {}


  /** True for the exact geometry classes covered by this codec, including curve subclasses. */
  public static boolean isSupportedGeometry(Geometry geometry) {
    if (geometry == null) {
      return false;
    }
    Class<?> type = geometry.getClass();
    return type == Point.class
        || type == LineString.class
        || type == LinearRing.class
        || type == Polygon.class
        || type == MultiPoint.class
        || type == MultiLineString.class
        || type == MultiPolygon.class
        || type == GeometryCollection.class
        || type == CircularString.class
        || type == CompoundCurve.class
        || type == CurvePolygon.class
        || type == MultiCurve.class
        || type == MultiSurface.class;
  }

  public static CoordinateLayout layoutOf(CoordinateSequence sequence) throws GeometryCodecException {
    if (sequence == null) {
      return CoordinateLayout.XY;
    }
    try {
      return CoordinateLayout.ofSequence(sequence.getDimension(), sequence.getMeasures());
    } catch (IllegalArgumentException e) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT, e.getMessage(), -1, e);
    }
  }

  /**
   * Layout for line/polygon sequences. Legacy JTS {@link CoordinateArraySequence} instances that
   * declare three dimensions but contain only NaN Z values are treated as XY. This preserves the
   * historical curve/line behaviour for ordinary JTS-created lines while explicit codec sequences
   * (which use packed sequences) retain all-NaN declared Z/M dimensions.
   */
  public static CoordinateLayout lineStringLayout(CoordinateSequence sequence)
      throws GeometryCodecException {
    CoordinateLayout layout = layoutOf(sequence);
    if (layout != CoordinateLayout.XYZ
        || !(sequence instanceof CoordinateArraySequence)
        || sequence.size() == 0) {
      return layout;
    }
    for (int i = 0; i < sequence.size(); i++) {
      if (!Double.isNaN(sequence.getZ(i))) {
        return layout;
      }
    }
    return CoordinateLayout.XY;
  }

  /** Layout of one primitive geometry. Collections are rejected by this method. */
  public static CoordinateLayout primitiveLayout(Geometry geometry) throws GeometryCodecException {
    if (geometry == null) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY, "Geometry must not be null");
    }
    if (geometry instanceof CircularString circularString) {
      return layoutOf(circularString.getControlPointSequence());
    }
    if (geometry instanceof CompoundCurve compoundCurve) {
      List<CoordinateLayout> layouts = new ArrayList<>();
      for (LineString component : compoundCurve.getComponents()) {
        layouts.add(primitiveLayout(component));
      }
      return consistentLayout(layouts, "COMPOUNDCURVE components");
    }
    if (geometry instanceof CurvePolygon curvePolygon) {
      List<CoordinateLayout> layouts = new ArrayList<>();
      for (LineString ring : curvePolygon.getCurveRings()) {
        layouts.add(primitiveLayout(ring));
      }
      return consistentLayout(layouts, "CURVEPOLYGON rings");
    }
    if (geometry instanceof Point point) {
      return layoutOf(point.getCoordinateSequence());
    }
    if (geometry instanceof LineString lineString) {
      return lineStringLayout(lineString.getCoordinateSequence());
    }
    if (geometry instanceof Polygon polygon) {
      List<CoordinateLayout> layouts = new ArrayList<>();
      layouts.add(lineStringLayout(polygon.getExteriorRing().getCoordinateSequence()));
      for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
        layouts.add(lineStringLayout(polygon.getInteriorRingN(i).getCoordinateSequence()));
      }
      return consistentLayout(layouts, "POLYGON rings");
    }
    throw new GeometryCodecException(
        GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY,
        "Not a primitive geometry: " + safeClassName(geometry));
  }

  /** Union layout of all members. Used for validation and header/marker selection only. */
  public static CoordinateLayout aggregateLayout(Geometry geometry) throws GeometryCodecException {
    if (geometry == null) {
      return CoordinateLayout.XY;
    }
    if (geometry instanceof GeometryCollection collection) {
      boolean z = false;
      boolean m = false;
      if (collection.getNumGeometries() == 0) {
        return CoordinateLayout.XY;
      }
      for (int i = 0; i < collection.getNumGeometries(); i++) {
        CoordinateLayout child = aggregateLayout(collection.getGeometryN(i));
        z |= child.hasZ();
        m |= child.hasM();
      }
      return CoordinateLayout.of(z, m);
    }
    return primitiveLayout(geometry);
  }

  /**
   * Checks types, layouts, finite XY ordinates and read limits. General JTS topology validity is
   * deliberately not checked.
   */
  public static void validateRepresentable(Geometry geometry) throws GeometryCodecException {
    validateRepresentable(geometry, GeometryReadLimits.defaults());
  }

  public static void validateRepresentable(Geometry geometry, GeometryReadLimits limits)
      throws GeometryCodecException {
    if (geometry == null) {
      return;
    }
    Counter counter = new Counter(limits);
    validateGeometry(geometry, counter);
  }

  private static void validateGeometry(Geometry geometry, Counter counter)
      throws GeometryCodecException {
    if (geometry == null) {
      return;
    }
    if (!isSupportedGeometry(geometry)) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY,
          "Unsupported geometry class: " + safeClassName(geometry));
    }
    if (counter.components >= counter.limits.maxComponents()) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_LIMIT_EXCEEDED,
          "Geometry component limit of " + counter.limits.maxComponents() + " exceeded");
    }
    counter.components++;

    if (geometry instanceof CircularString circularString) {
      CoordinateSequence controls = circularString.getControlPointSequence();
      if (controls.size() == 0) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE,
            "Empty CIRCULARSTRING is not representable");
      }
      if (controls.size() < 3 || controls.size() % 2 == 0) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_INVALID_WKT,
            "CIRCULARSTRING requires an odd number of at least 3 control points");
      }
      validateSequence(controls, counter);
      return;
    }
    if (geometry instanceof CompoundCurve compoundCurve) {
      List<LineString> components = compoundCurve.getComponents();
      if (components.isEmpty()) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE,
            "Empty COMPOUNDCURVE is not representable");
      }
      LayoutBox box = new LayoutBox();
      for (LineString component : components) {
        box.accept(primitiveLayout(component));
        validateGeometry(component, counter);
      }
      box.requireConsistent("COMPOUNDCURVE components");
      return;
    }
    if (geometry instanceof CurvePolygon curvePolygon) {
      List<LineString> rings = curvePolygon.getCurveRings();
      if (rings.isEmpty()) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE,
            "Empty CURVEPOLYGON is not representable");
      }
      LayoutBox box = new LayoutBox();
      for (LineString ring : rings) {
        box.accept(primitiveLayout(ring));
        validateGeometry(ring, counter);
      }
      box.requireConsistent("CURVEPOLYGON rings");
      return;
    }
    if (geometry instanceof MultiCurve multiCurve) {
      for (LineString curve : multiCurve.getCurves()) {
        primitiveLayout(curve);
        validateGeometry(curve, counter);
      }
      return;
    }
    if (geometry instanceof MultiSurface multiSurface) {
      for (Polygon surface : multiSurface.getSurfaces()) {
        primitiveLayout(surface);
        validateGeometry(surface, counter);
      }
      return;
    }
    if (geometry instanceof Point point) {
      CoordinateSequence sequence = point.getCoordinateSequence();
      if (sequence.size() > 1) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_INVALID_WKT, "POINT must contain at most one coordinate");
      }
      validateSequence(sequence, counter);
      return;
    }
    if (geometry instanceof LineString lineString) {
      CoordinateSequence sequence = lineString.getCoordinateSequence();
      if (sequence.size() == 1) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_INVALID_WKT,
            "Non-empty LINESTRING requires at least two coordinates");
      }
      validateSequence(sequence, counter);
      return;
    }
    if (geometry instanceof Polygon polygon) {
      LayoutBox box = new LayoutBox();
      box.accept(layoutOf(polygon.getExteriorRing().getCoordinateSequence()));
      validateSequence(polygon.getExteriorRing().getCoordinateSequence(), counter);
      for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
        box.accept(layoutOf(polygon.getInteriorRingN(i).getCoordinateSequence()));
        validateSequence(polygon.getInteriorRingN(i).getCoordinateSequence(), counter);
      }
      box.requireConsistent("POLYGON rings");
      return;
    }
    if (geometry instanceof MultiPoint multiPoint) {
      for (int i = 0; i < multiPoint.getNumGeometries(); i++) {
        Geometry child = multiPoint.getGeometryN(i);
        primitiveLayout(child);
        validateGeometry(child, counter);
      }
      return;
    }
    if (geometry instanceof MultiLineString multiLineString) {
      for (int i = 0; i < multiLineString.getNumGeometries(); i++) {
        Geometry child = multiLineString.getGeometryN(i);
        primitiveLayout(child);
        validateGeometry(child, counter);
      }
      return;
    }
    if (geometry instanceof MultiPolygon multiPolygon) {
      for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
        Geometry child = multiPolygon.getGeometryN(i);
        primitiveLayout(child);
        validateGeometry(child, counter);
      }
      return;
    }
    if (geometry instanceof GeometryCollection collection) {
      for (int i = 0; i < collection.getNumGeometries(); i++) {
        validateGeometry(collection.getGeometryN(i), counter);
      }
      return;
    }
    throw new GeometryCodecException(
        GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY,
        "Unsupported geometry class: " + safeClassName(geometry));
  }

  private static void validateSequence(CoordinateSequence sequence, Counter counter)
      throws GeometryCodecException {
    if (sequence == null) {
      return;
    }
    layoutOf(sequence);
    long count = sequence.size();
    counter.coordinates += count;
    if (counter.coordinates > counter.limits.maxCoordinates()) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_LIMIT_EXCEEDED,
          "Coordinate limit of " + counter.limits.maxCoordinates() + " exceeded");
    }
    for (int i = 0; i < sequence.size(); i++) {
      double x = sequence.getX(i);
      double y = sequence.getY(i);
      if (!Double.isFinite(x) || !Double.isFinite(y)) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_NONFINITE_COORDINATE,
            "X and Y ordinates must be finite at coordinate " + i);
      }
      if (sequence.hasZ()) {
        double z = sequence.getZ(i);
        if (Double.isInfinite(z)) {
          throw new GeometryCodecException(
              GeometryCodecErrorCode.GC_NONFINITE_COORDINATE,
              "Z ordinate must not be infinite at coordinate " + i);
        }
      }
      if (sequence.hasM()) {
        double m = sequence.getM(i);
        if (Double.isInfinite(m)) {
          throw new GeometryCodecException(
              GeometryCodecErrorCode.GC_NONFINITE_COORDINATE,
              "M ordinate must not be infinite at coordinate " + i);
        }
      }
    }
  }

  private static CoordinateLayout consistentLayout(
      List<CoordinateLayout> layouts, String role) throws GeometryCodecException {
    if (layouts.isEmpty()) {
      return CoordinateLayout.XY;
    }
    CoordinateLayout first = layouts.get(0);
    for (CoordinateLayout layout : layouts) {
      if (layout != first) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT,
            "Inconsistent coordinate layouts in " + role);
      }
    }
    return first;
  }

  private static String safeClassName(Geometry geometry) {
    return geometry == null ? "null" : geometry.getClass().getName();
  }

  private static final class Counter {
    private final GeometryReadLimits limits;
    private long coordinates;
    private long components;

    private Counter(GeometryReadLimits limits) {
      this.limits = limits == null ? GeometryReadLimits.defaults() : limits;
    }
  }

  private static final class LayoutBox {
    private CoordinateLayout layout;

    private void accept(CoordinateLayout next) throws GeometryCodecException {
      if (layout == null) {
        layout = next;
      } else if (layout != next) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT,
            "Inconsistent coordinate layouts: " + layout + " and " + next);
      }
    }

    private void requireConsistent(String role) throws GeometryCodecException {
      // Empty multi/collection geometries have no member layout; XY is their representable form.
    }
  }
}
