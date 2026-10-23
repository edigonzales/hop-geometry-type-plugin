package com.atolcd.hop.gis.geometry.codec;

import org.locationtech.jts.geom.Geometry;

/** Geometry plus the context that cannot be represented by a numeric SRID alone. */
public record GeoJsonReadResult(Geometry geometry, GeoJsonContext context) {}
