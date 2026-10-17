package com.atolcd.hop.gis.geometry.curve;

import com.atolcd.hop.gis.geometry.codec.GeometryCodecException;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.io.ParseException;

/** Dispatches between standard JTS codecs and the curve-aware SQL/MM codecs. */
public final class CurveGeometrySupport {
  private CurveGeometrySupport() {}

  public static boolean isCurveGeometry(Geometry geometry) {
    return geometry instanceof CircularString
        || geometry instanceof CompoundCurve
        || geometry instanceof CurvePolygon
        || geometry instanceof MultiCurve
        || geometry instanceof MultiSurface;
  }

  /** Recursive curve detection, including ordinary GeometryCollections with curve members. */
  public static boolean containsCurveGeometry(Geometry geometry) {
    if (geometry == null) {
      return false;
    }
    if (isCurveGeometry(geometry)) {
      return true;
    }
    if (geometry instanceof GeometryCollection collection) {
      for (int i = 0; i < collection.getNumGeometries(); i++) {
        if (containsCurveGeometry(collection.getGeometryN(i))) {
          return true;
        }
      }
    }
    return false;
  }

  public static String writeWkt(Geometry geometry) {
    if (!containsCurveGeometry(geometry)) {
      throw new IllegalArgumentException(
          "Not a supported curve geometry: " + geometry.getClass().getName());
    }
    return new CurveWktWriter().write(geometry);
  }

  public static byte[] writeWkb(Geometry geometry) {
    if (containsCurveGeometry(geometry)) {
      return new CurveWkbWriter().write(geometry);
    }
    return LinearGeometryCodec.writeWkb(geometry);
  }

  public static Geometry readWkb(byte[] wkb) throws ParseException {
    try {
      return new CurveWkbReader().readChecked(wkb);
    } catch (GeometryCodecException e) {
      ParseException parseException = new ParseException(e.getMessage());
      parseException.initCause(e);
      throw parseException;
    }
  }

  public static Geometry copy(Geometry geometry) {
    if (geometry == null) {
      return null;
    }
    Geometry copy = geometry.copy();
    copy.setSRID(geometry.getSRID());
    return copy;
  }

  /** Converts exact curves with a maximum XY chord deviation in coordinate units. */
  public static Geometry linearize(Geometry geometry, double maxError) {
    if (!Double.isFinite(maxError) || maxError <= 0)
      throw new IllegalArgumentException("Maximum deviation must be positive and finite");
    if (geometry == null) return null;
    var f = geometry.getFactory();
    Geometry result;
    if (geometry instanceof CircularString c) {
      java.util.List<org.locationtech.jts.geom.Coordinate> points = new java.util.ArrayList<>();
      for (ArcSegment arc : c.getArcSegments()) {
        var part = arc.linearize(maxError);
        for (int i = points.isEmpty() ? 0 : 1; i < part.length; i++) points.add(part[i]);
      }
      result = f.createLineString(points.toArray(org.locationtech.jts.geom.Coordinate[]::new));
    } else if (geometry instanceof CompoundCurve c) {
      java.util.List<org.locationtech.jts.geom.Coordinate> points = new java.util.ArrayList<>();
      for (var component : c.getComponents()) {
        var part = linearize(component, maxError).getCoordinates();
        if (!points.isEmpty() && part.length > 0 && !points.getLast().equals2D(part[0]))
          throw new IllegalArgumentException("Disconnected compound curve");
        for (int i = points.isEmpty() ? 0 : 1; i < part.length; i++) points.add(part[i]);
      }
      result = f.createLineString(points.toArray(org.locationtech.jts.geom.Coordinate[]::new));
    } else if (geometry instanceof CurvePolygon p) {
      var rings = p.getCurveRings();
      var shell = f.createLinearRing(linearize(rings.getFirst(), maxError).getCoordinates());
      var holes = new org.locationtech.jts.geom.LinearRing[rings.size() - 1];
      for (int i = 1; i < rings.size(); i++)
        holes[i - 1] = f.createLinearRing(linearize(rings.get(i), maxError).getCoordinates());
      result = f.createPolygon(shell, holes);
    } else if (geometry instanceof MultiCurve c) {
      result =
          f.createMultiLineString(
              c.getCurves().stream()
                  .map(g -> (org.locationtech.jts.geom.LineString) linearize(g, maxError))
                  .toArray(org.locationtech.jts.geom.LineString[]::new));
    } else if (geometry instanceof MultiSurface c) {
      result =
          f.createMultiPolygon(
              c.getSurfaces().stream()
                  .map(g -> (org.locationtech.jts.geom.Polygon) linearize(g, maxError))
                  .toArray(org.locationtech.jts.geom.Polygon[]::new));
    } else if (geometry instanceof org.locationtech.jts.geom.GeometryCollection c) {
      var children = new Geometry[c.getNumGeometries()];
      for (int i = 0; i < children.length; i++)
        children[i] = linearize(c.getGeometryN(i), maxError);
      if (c instanceof org.locationtech.jts.geom.MultiLineString)
        result =
            f.createMultiLineString(
                java.util.Arrays.copyOf(
                    children, children.length, org.locationtech.jts.geom.LineString[].class));
      else if (c instanceof org.locationtech.jts.geom.MultiPolygon)
        result =
            f.createMultiPolygon(
                java.util.Arrays.copyOf(
                    children, children.length, org.locationtech.jts.geom.Polygon[].class));
      else if (c instanceof org.locationtech.jts.geom.MultiPoint) result = c.copy();
      else result = f.createGeometryCollection(children);
    } else result = geometry.copy();
    result.setSRID(geometry.getSRID());
    return result;
  }
}
