package com.atolcd.hop.gis.geometry.curve;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.locationtech.jts.geom.Geometry;
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

  public static String writeWkt(Geometry geometry) {
    if (!isCurveGeometry(geometry)) {
      throw new IllegalArgumentException(
          "Not a supported curve geometry: " + geometry.getClass().getName());
    }
    return new CurveWktWriter().write(geometry);
  }

  public static byte[] writeWkb(Geometry geometry) {
    if (isCurveGeometry(geometry)) {
      return new CurveWkbWriter().write(geometry);
    }
    boolean includeSrid = geometry.getSRID() != 0;
    return LinearGeometryCodec.writeWkb(geometry);
  }

  public static Geometry readWkb(byte[] wkb) throws ParseException {
    int rawType = readRawType(wkb);
    int type = rawType & CurveWkbReader.EWKB_TYPE_MASK;
    int baseType = type >= 1000 ? type % 1000 : type;

    if (isSupportedCurveType(baseType)) {
      try {
        return new CurveWkbReader().read(wkb);
      } catch (IllegalArgumentException e) {
        ParseException parseException = new ParseException(e.getMessage());
        parseException.initCause(e);
        throw parseException;
      }
    }

    return LinearGeometryCodec.readWkb(wkb);
  }

  public static Geometry copy(Geometry geometry) {
    Geometry copy;
    if (isCurveGeometry(geometry)) {
      copy = new CurveWkbReader(geometry.getFactory()).read(new CurveWkbWriter().write(geometry));
    } else {
      copy = geometry.copy();
    }
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

  private static boolean isSupportedCurveType(int type) {
    return type == CurveWkbReader.WKB_CIRCULARSTRING
        || type == CurveWkbReader.WKB_COMPOUNDCURVE
        || type == CurveWkbReader.WKB_CURVEPOLYGON
        || type == CurveWkbReader.WKB_MULTICURVE
        || type == CurveWkbReader.WKB_MULTISURFACE;
  }

  private static int readRawType(byte[] wkb) throws ParseException {
    if (wkb == null || wkb.length < 5) {
      throw new ParseException("WKB is too short");
    }

    ByteOrder order =
        switch (wkb[0] & 0xff) {
          case 0 -> ByteOrder.BIG_ENDIAN;
          case 1 -> ByteOrder.LITTLE_ENDIAN;
          default -> throw new ParseException("Invalid WKB byte order: " + (wkb[0] & 0xff));
        };

    return ByteBuffer.wrap(wkb, 1, Integer.BYTES).order(order).getInt();
  }
}
