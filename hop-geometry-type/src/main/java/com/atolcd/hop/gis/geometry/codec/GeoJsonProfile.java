package com.atolcd.hop.gis.geometry.codec;

import java.util.Map;

/** Explicit interchange dialect. AUTO is a read-only profile. */
public enum GeoJsonProfile {
  AUTO, RFC7946, LEGACY2008, JSONFG10;

  /** Detects document markers, then inherited context; unmarked documents are RFC 7946. */
  public static GeoJsonProfile detect(Map<String,Object> object, GeoJsonContext context)
      throws GeometryCodecException {
    Object conforms = object.get("conformsTo");
    boolean jsonFg = false;
    if (conforms instanceof java.util.List<?> list) {
      for (Object value : list) {
        String uri = String.valueOf(value);
        if (uri.contains("/spec/json-fg-")) {
          if (!uri.startsWith("http://www.opengis.net/spec/json-fg-1/1.0/conf/"))
            throw new GeometryCodecException(GeometryCodecErrorCode.GC_INVALID_JSON, "Unsupported JSON-FG version");
          jsonFg = true;
        }
      }
    }
    if (jsonFg) return JSONFG10;
    if (object.containsKey("coordRefSys") || object.containsKey("place") || object.containsKey("measures")) return JSONFG10;
    if (object.containsKey("crs")) return LEGACY2008;
    if (context != null && context.profile() != AUTO) return context.profile();
    return RFC7946;
  }
}
