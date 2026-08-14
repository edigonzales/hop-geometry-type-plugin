package com.atolcd.hop.gis.geometry.curve;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ByteOrderValues;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBReader;
import org.locationtech.jts.io.WKBWriter;

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
    return new WKBWriter(2, ByteOrderValues.BIG_ENDIAN, includeSrid).write(geometry);
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

    return new WKBReader().read(wkb);
  }

  public static Geometry copy(Geometry geometry) {
    Geometry copy;
    if (isCurveGeometry(geometry)) {
      copy = new CurveWkbReader(geometry.getFactory()).read(new CurveWkbWriter().write(geometry));
    } else {
      copy = new GeometryFactory().createGeometry(geometry);
    }
    copy.setSRID(geometry.getSRID());
    return copy;
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
