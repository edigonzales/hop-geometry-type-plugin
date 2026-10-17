package com.atolcd.hop.gis.utils;

import com.atolcd.hop.gis.geometry.codec.GeometryCodec;
import com.atolcd.hop.gis.geometry.codec.GeometryCodecException;
import com.atolcd.hop.gis.geometry.codec.GeometryReadLimits;
import org.locationtech.jts.geom.Geometry;

public final class GeometryUtils {

  private GeometryUtils() {}

  /**
   * Legacy WKT/EWKT entry point used by Select Values. The shared strict parser is used, but the
   * historical EMPTY→NULL normalization is deliberately retained. New transport callers use
   * {@link GeometryCodec} directly and therefore preserve EMPTY.
   */
  public static Geometry getGeometryFromEWKT(String ewkt) throws Exception {
    if (ewkt == null || ewkt.isBlank()) {
      return null;
    }

    try {
      Geometry geometry =
          new GeometryCodec(GeometryReadLimits.defaults()).readText(ewkt);
      return geometry == null || geometry.isEmpty() ? null : geometry;
    } catch (GeometryCodecException e) {
      throw new Exception("The value is not a valid WKT/EWKT string", e);
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
