package com.atolcd.hop.gis.geometry.codec;

import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

/** Point construction from explicit field values and point-only coordinate extraction. */
public final class PointCoordinateSupport {
  private PointCoordinateSupport() {}

  /**
   * Creates a point from explicit ordinates. Returns NULL only for a completely missing tuple.
   * Partially missing tuples and non-finite values are named errors.
   */
  public static Point create(PointCoordinates coordinates, int srid, GeometryFactory factory)
      throws GeometryCodecException {
    if (srid < 0) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_INVALID_SRID, "SRID must not be negative: " + srid);
    }
    if (coordinates == null) {
      return null;
    }
    if (factory == null) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_TYPE_MISMATCH, "GeometryFactory must not be null");
    }

    CoordinateLayout layout = coordinates.layout();
    Double x = coordinates.x();
    Double y = coordinates.y();
    Double z = coordinates.z();
    Double m = coordinates.m();

    if (z != null && !layout.hasZ()) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_TYPE_MISMATCH,
          "Z value supplied for layout " + layout);
    }
    if (m != null && !layout.hasM()) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_TYPE_MISMATCH,
          "M value supplied for layout " + layout);
    }

    boolean xMissing = x == null;
    boolean yMissing = y == null;
    boolean zMissing = !layout.hasZ() || isMissing(z);
    boolean mMissing = !layout.hasM() || isMissing(m);

    if (xMissing && yMissing && zMissing && mMissing) {
      return null;
    }
    if (xMissing || yMissing) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_PARTIAL_COORDINATE_NULL,
          "X and Y must either both be present or both be NULL");
    }
    requireFinite(x, "X");
    requireFinite(y, "Y");
    requireFinite(z, "Z", true);
    requireFinite(m, "M", true);

    CoordinateSequence sequence =
        factory
            .getCoordinateSequenceFactory()
            .create(1, layout.dimension(), layout.measures());
    sequence.setOrdinate(0, CoordinateSequence.X, x);
    sequence.setOrdinate(0, CoordinateSequence.Y, y);
    if (layout.hasZ()) {
      sequence.setOrdinate(0, layout.zIndex(), z == null ? Double.NaN : z);
    }
    if (layout.hasM()) {
      sequence.setOrdinate(0, layout.mIndex(), m == null ? Double.NaN : m);
    }
    Point point = factory.createPoint(sequence);
    point.setSRID(srid);
    return point;
  }

  /** Extracts a point's declared ordinates. Non-points (including empty non-points) fail. */
  public static PointCoordinates extract(Geometry geometry) throws GeometryCodecException {
    if (geometry == null) {
      return new PointCoordinates(null, null, null, null, CoordinateLayout.XY);
    }
    if (!(geometry instanceof Point point)) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_NOT_A_POINT,
          "Coordinate extraction requires a Point, found " + geometry.getGeometryType());
    }
    CoordinateSequence sequence = point.getCoordinateSequence();
    CoordinateLayout layout = GeometryLayoutSupport.layoutOf(sequence);
    if (sequence.size() == 0) {
      return new PointCoordinates(null, null, null, null, layout);
    }
    if (sequence.size() != 1) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_TYPE_MISMATCH,
          "POINT must contain at most one coordinate");
    }
    double x = sequence.getX(0);
    double y = sequence.getY(0);
    if (!Double.isFinite(x) || !Double.isFinite(y)) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_NONFINITE_COORDINATE,
          "Point X and Y ordinates must be finite");
    }
    Double z = null;
    if (layout.hasZ()) {
      double value = sequence.getZ(0);
      if (Double.isInfinite(value)) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_NONFINITE_COORDINATE, "Point Z ordinate is infinite");
      }
      if (!Double.isNaN(value)) {
        z = value;
      }
    }
    Double m = null;
    if (layout.hasM()) {
      double value = sequence.getM(0);
      if (Double.isInfinite(value)) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_NONFINITE_COORDINATE, "Point M ordinate is infinite");
      }
      if (!Double.isNaN(value)) {
        m = value;
      }
    }
    return new PointCoordinates(x, y, z, m, layout);
  }

  private static boolean isMissing(Double value) {
    return value == null || Double.isNaN(value);
  }

  private static void requireFinite(Double value, String role) throws GeometryCodecException {
    requireFinite(value, role, false);
  }

  private static void requireFinite(Double value, String role, boolean allowNan)
      throws GeometryCodecException {
    if (value == null) {
      return;
    }
    if (allowNan ? Double.isInfinite(value) : !Double.isFinite(value)) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_NONFINITE_COORDINATE, role + " ordinate must be finite");
    }
  }
}
