package com.atolcd.hop.gis.geometry.codec;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CompoundCurve;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import com.atolcd.hop.gis.geometry.curve.MultiCurve;
import com.atolcd.hop.gis.geometry.curve.MultiSurface;
import java.util.LinkedHashSet;
import java.util.Set;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/** SRID consistency and copy-on-write SRID assignment. */
public final class GeometrySridSupport {
  private GeometrySridSupport() {}

  /**
   * Implements the root/child rules from the functional contract without mutating the geometry.
   * Returns 0 for NULL or an unknown SRID.
   */
  public static int resolveConsistentSrid(Geometry geometry) throws GeometryCodecException {
    if (geometry == null) {
      return 0;
    }
    Set<Integer> positives = new LinkedHashSet<>();
    collectSrids(geometry, positives);
    if (positives.size() > 1) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_MIXED_SRID,
          "Geometry tree contains conflicting positive SRIDs: " + positives);
    }
    if (!positives.isEmpty()) {
      return positives.iterator().next();
    }
    return geometry.getSRID() > 0 ? geometry.getSRID() : 0;
  }

  /**
   * Returns an exact copy with the target SRID assigned recursively. The source geometry is never
   * modified.
   */
  public static Geometry copyWithSrid(Geometry geometry, int srid) throws GeometryCodecException {
    if (srid < 0) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_INVALID_SRID, "SRID must not be negative: " + srid);
    }
    if (geometry == null) {
      return null;
    }
    Geometry copy = copyExact(geometry);
    setSridRecursively(copy, srid);
    return copy;
  }

  static Geometry copyExact(Geometry geometry) throws GeometryCodecException {
    if (geometry == null) {
      return null;
    }
    try {
      Geometry copy = geometry.copy();
      copy.setSRID(geometry.getSRID());
      return copy;
    } catch (RuntimeException e) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY,
          "Unable to copy geometry: " + geometry.getClass().getName(),
          -1,
          e);
    }
  }

  static void setSridRecursively(Geometry geometry, int srid) {
    if (geometry == null) {
      return;
    }
    geometry.setSRID(srid);
    if (geometry instanceof CircularString) {
      return;
    }
    if (geometry instanceof CompoundCurve compoundCurve) {
      for (LineString component : compoundCurve.getComponents()) {
        setSridRecursively(component, srid);
      }
      return;
    }
    if (geometry instanceof CurvePolygon curvePolygon) {
      for (LineString ring : curvePolygon.getCurveRings()) {
        setSridRecursively(ring, srid);
      }
      curvePolygon.getExteriorRing().setSRID(srid);
      for (int i = 0; i < curvePolygon.getNumInteriorRing(); i++) {
        curvePolygon.getInteriorRingN(i).setSRID(srid);
      }
      return;
    }
    if (geometry instanceof MultiCurve multiCurve) {
      for (LineString curve : multiCurve.getCurves()) {
        setSridRecursively(curve, srid);
      }
      return;
    }
    if (geometry instanceof MultiSurface multiSurface) {
      for (Polygon surface : multiSurface.getSurfaces()) {
        setSridRecursively(surface, srid);
      }
      return;
    }
    if (geometry instanceof Polygon polygon) {
      polygon.getExteriorRing().setSRID(srid);
      for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
        polygon.getInteriorRingN(i).setSRID(srid);
      }
      return;
    }
    if (geometry instanceof Point || geometry instanceof LineString) {
      return;
    }
    if (geometry instanceof GeometryCollection collection) {
      for (int i = 0; i < collection.getNumGeometries(); i++) {
        setSridRecursively(collection.getGeometryN(i), srid);
      }
    }
  }

  private static void collectSrids(Geometry geometry, Set<Integer> positives)
      throws GeometryCodecException {
    if (geometry == null) {
      return;
    }
    int srid = geometry.getSRID();
    if (srid < 0) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_INVALID_SRID, "Geometry contains a negative SRID: " + srid);
    }
    if (srid > 0) {
      positives.add(srid);
    }
    if (geometry instanceof CircularString) {
      return;
    }
    if (geometry instanceof CompoundCurve compoundCurve) {
      for (LineString component : compoundCurve.getComponents()) {
        collectSrids(component, positives);
      }
      return;
    }
    if (geometry instanceof CurvePolygon curvePolygon) {
      for (LineString ring : curvePolygon.getCurveRings()) {
        collectSrids(ring, positives);
      }
      return;
    }
    if (geometry instanceof MultiCurve multiCurve) {
      for (LineString curve : multiCurve.getCurves()) {
        collectSrids(curve, positives);
      }
      return;
    }
    if (geometry instanceof MultiSurface multiSurface) {
      for (Polygon surface : multiSurface.getSurfaces()) {
        collectSrids(surface, positives);
      }
      return;
    }
    if (geometry instanceof Polygon polygon) {
      collectSrids(polygon.getExteriorRing(), positives);
      for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
        collectSrids(polygon.getInteriorRingN(i), positives);
      }
      return;
    }
    if (geometry instanceof Point || geometry instanceof LineString) {
      return;
    }
    if (geometry instanceof MultiPoint
        || geometry instanceof MultiLineString
        || geometry instanceof MultiPolygon
        || geometry instanceof GeometryCollection) {
      for (int i = 0; i < geometry.getNumGeometries(); i++) {
        collectSrids(geometry.getGeometryN(i), positives);
      }
      return;
    }
    throw new GeometryCodecException(
        GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY,
        "Unsupported geometry class for SRID resolution: " + geometry.getClass().getName());
  }
}
