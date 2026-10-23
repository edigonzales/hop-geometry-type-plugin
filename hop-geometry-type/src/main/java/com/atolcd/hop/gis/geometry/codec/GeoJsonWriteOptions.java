package com.atolcd.hop.gis.geometry.codec;

/** Explicit write dialect and opt-in loss policies. No reprojection is performed. */
public record GeoJsonWriteOptions(
    GeoJsonProfile profile, GeoJsonContext context, boolean dropZ, boolean dropM,
    boolean emptyToNull) {
  public GeoJsonWriteOptions {
    profile = profile == null ? GeoJsonProfile.RFC7946 : profile;
    if (profile == GeoJsonProfile.AUTO) throw new IllegalArgumentException("AUTO is read-only");
    context = context == null ? GeoJsonContext.empty() : context;
  }
  public static GeoJsonWriteOptions defaults() {
    return new GeoJsonWriteOptions(GeoJsonProfile.RFC7946, GeoJsonContext.empty(), false, false, false);
  }
}
