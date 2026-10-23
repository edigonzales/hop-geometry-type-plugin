package com.atolcd.hop.gis.geometry.codec;

/** Read profile and inherited document/feature context. Null components use safe defaults. */
public record GeoJsonReadOptions(GeoJsonProfile profile, GeoJsonContext context) {
  public GeoJsonReadOptions {
    profile = profile == null ? GeoJsonProfile.AUTO : profile;
    context = context == null ? GeoJsonContext.empty() : context;
  }
  public static GeoJsonReadOptions defaults() {
    return new GeoJsonReadOptions(GeoJsonProfile.AUTO, GeoJsonContext.empty());
  }
}
