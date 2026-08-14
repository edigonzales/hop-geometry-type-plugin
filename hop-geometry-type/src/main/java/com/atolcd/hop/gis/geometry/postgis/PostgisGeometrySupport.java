package com.atolcd.hop.gis.geometry.postgis;

import com.atolcd.hop.gis.geometry.curve.CurveGeometrySupport;
import com.atolcd.hop.gis.utils.GeometryUtils;
import java.util.HexFormat;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTWriter;

/** Curve-aware encoding and decoding for native PostGIS geometry JDBC values. */
public final class PostgisGeometrySupport {
  private static final HexFormat HEX = HexFormat.of();

  private PostgisGeometrySupport() {}

  /**
   * Decodes the textual representation returned by PGJDBC for a native PostGIS geometry column.
   * PostGIS normally returns hex EWKB; ordinary EWKT is accepted as a compatibility fallback.
   */
  public static Geometry read(String value) throws ParseException {
    if (value == null) {
      return null;
    }

    String text = value.trim();
    String hex = text.startsWith("\\x") ? text.substring(2) : text;
    if (isHexWkb(hex)) {
      try {
        return CurveGeometrySupport.readWkb(HEX.parseHex(hex));
      } catch (IllegalArgumentException e) {
        ParseException parseException = new ParseException("Invalid PostGIS hex EWKB");
        parseException.initCause(e);
        throw parseException;
      }
    }

    try {
      return GeometryUtils.getGeometryFromEWKT(text);
    } catch (Exception e) {
      ParseException parseException =
          new ParseException("Unsupported PostGIS geometry representation: " + text);
      parseException.initCause(e);
      throw parseException;
    }
  }

  /** Encodes a Hop/JTS geometry as EWKT without linearizing supported SQL/MM curve types. */
  public static String write(Geometry geometry) {
    if (geometry == null) {
      return null;
    }

    String wkt;
    if (CurveGeometrySupport.isCurveGeometry(geometry)) {
      wkt = CurveGeometrySupport.writeWkt(geometry);
    } else if (GeometryUtils.getCoordinateDimension(geometry) == 3) {
      wkt = new WKTWriter(3).write(geometry);
    } else {
      wkt = new WKTWriter(2).write(geometry);
    }

    if (geometry.getSRID() > 0) {
      return "SRID=" + geometry.getSRID() + ";" + wkt;
    }
    return wkt;
  }

  private static boolean isHexWkb(String value) {
    if (value.length() < 10 || (value.length() & 1) != 0) {
      return false;
    }
    if (!(value.startsWith("00") || value.startsWith("01"))) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (Character.digit(value.charAt(i), 16) < 0) {
        return false;
      }
    }
    return true;
  }
}
