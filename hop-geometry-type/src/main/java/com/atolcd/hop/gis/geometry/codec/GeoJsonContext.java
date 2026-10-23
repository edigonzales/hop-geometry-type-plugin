package com.atolcd.hop.gis.geometry.codec;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Versioned JSON-serializable geometry context, kept separate from Geometry.userData.
 * coordRefSys is a CRS string or a JSON-FG horizontal/vertical compound array.
 */
public record GeoJsonContext(
    GeoJsonProfile profile, Object coordRefSys, Map<String, Object> measures) {
  public static final String JSONFG_CORE = "http://www.opengis.net/spec/json-fg-1/1.0/conf/core";
  public static final String JSONFG_CURVES = "http://www.opengis.net/spec/json-fg-1/1.0/conf/circular-arcs";
  public static final String JSONFG_MEASURES = "http://www.opengis.net/spec/json-fg-1/1.0/conf/measures";
  public static final String JSONFG_TYPES_SCHEMAS = "http://www.opengis.net/spec/json-fg-1/1.0/conf/types-schemas";

  public GeoJsonContext {
    profile = profile == null ? GeoJsonProfile.AUTO : profile;
    measures = measures == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(measures));
    if (coordRefSys instanceof java.util.List<?> list) coordRefSys = java.util.List.copyOf(list);
  }
  public static GeoJsonContext empty() {
    return new GeoJsonContext(GeoJsonProfile.AUTO, null, Map.of());
  }
  public Map<String, Object> toMap() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("version", 1);
    result.put("profile", profile.name());
    if (coordRefSys != null) result.put("coordRefSys", coordRefSys);
    if (!measures.isEmpty()) result.put("measures", measures);
    return result;
  }
  public String toJson() throws GeometryCodecException { return JsonSupport.stringify(toMap()); }
  public static GeoJsonContext fromJson(String json) throws GeometryCodecException {
    return json == null || json.isBlank() ? empty() : fromMap(JsonSupport.parseObject(json));
  }
  public static GeoJsonContext fromMap(Map<String, Object> value) throws GeometryCodecException {
    if (value == null || value.isEmpty()) return empty();
    Object version = value.get("version");
    if (!(version instanceof Number n) || !"1".equals(n.toString()))
      throw new GeometryCodecException(GeometryCodecErrorCode.GC_INVALID_JSON, "Unsupported geometry context version");
    try {
      GeoJsonProfile profile = GeoJsonProfile.valueOf(String.valueOf(value.get("profile")));
      Object m = value.get("measures");
      if (m != null && !(m instanceof Map<?, ?>)) throw new IllegalArgumentException("measures must be an object");
      @SuppressWarnings("unchecked") Map<String,Object> measures = (Map<String,Object>) m;
      return new GeoJsonContext(profile, value.get("coordRefSys"), measures);
    } catch (IllegalArgumentException e) {
      throw new GeometryCodecException(GeometryCodecErrorCode.GC_INVALID_JSON, "Invalid geometry context", -1, e);
    }
  }
}
