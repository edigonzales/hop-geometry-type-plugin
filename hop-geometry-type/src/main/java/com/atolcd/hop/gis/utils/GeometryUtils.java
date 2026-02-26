package com.atolcd.hop.gis.utils;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;

public final class GeometryUtils {

  private GeometryUtils() {}

  public static Geometry getGeometryFromEWKT(String ewkt) throws Exception {
    if (ewkt == null || ewkt.isBlank()) {
      return null;
    }

    try {
      String[] parts = ewkt.split(";", 2);
      if (parts.length == 2 && parts[0].toUpperCase().startsWith("SRID=")) {
        int srid = Integer.parseInt(parts[0].substring(5).trim());
        Geometry geometry = new WKTReader().read(parts[1]);
        geometry.setSRID(srid);
        return geometry.isEmpty() ? null : geometry;
      }

      Geometry geometry = new WKTReader().read(ewkt);
      return geometry.isEmpty() ? null : geometry;
    } catch (ParseException | NumberFormatException e) {
      throw new Exception("The value \"" + ewkt + "\" is not a WKT/EWKT valid string", e);
    }
  }

  public static int getCoordinateDimension(Geometry geometry) {
    if (geometry == null || geometry.isEmpty()) {
      return 0;
    }
    double z = geometry.getCoordinate().getZ();
    return Double.isNaN(z) ? 2 : 3;
  }
}
