package com.atolcd.hop.gis.geometry.codec;

import com.atolcd.hop.gis.geometry.curve.CurveWktReader;
import com.atolcd.hop.gis.geometry.curve.CurveWktWriter;
import java.util.Objects;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;

/** WKT/EWKT facade with strict SRID-prefix handling and layout-preserving output. */
public final class GeometryTextCodec {
  private final GeometryFactory factory;
  private final GeometryReadLimits limits;

  public GeometryTextCodec(GeometryReadLimits limits) {
    this(newGeometryFactory(), limits);
  }

  public GeometryTextCodec(GeometryFactory factory, GeometryReadLimits limits) {
    this.factory = factory == null ? newGeometryFactory() : factory;
    this.limits = limits == null ? GeometryReadLimits.defaults() : limits;
  }

  public Geometry read(String text) throws GeometryCodecException {
    if (text == null || text.isBlank()) {
      return null;
    }
    EwktParts parts = splitSridPrefix(text);
    Geometry geometry = readGeometryBody(parts.body());
    if (parts.explicitSrid() != null) {
      geometry.setSRID(parts.explicitSrid());
    }
    GeometryLayoutSupport.validateRepresentable(geometry, limits);
    GeometrySridSupport.resolveConsistentSrid(geometry);
    return geometry;
  }

  public String write(Geometry geometry, WktWriteOptions options) throws GeometryCodecException {
    if (geometry == null) {
      return null;
    }
    if (options == null) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_TYPE_MISMATCH, "WKT write options must not be null");
    }
    GeometryLayoutSupport.validateRepresentable(geometry, limits);
    int srid = GeometrySridSupport.resolveConsistentSrid(geometry);
    String body = writeGeometryBody(geometry);
    return switch (options.sridEncoding()) {
      case OMIT -> body;
      case IF_KNOWN -> srid > 0 ? "SRID=" + srid + ";" + body : body;
      case ALWAYS -> "SRID=" + srid + ";" + body;
    };
  }

  EwktParts splitSridPrefix(String text) throws GeometryCodecException {
    String value = text == null ? null : text.strip();
    if (value == null || value.isEmpty()) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_INVALID_WKT, "Empty WKT input");
    }
    if (value.regionMatches(true, 0, "SRID=", 0, 5)) {
      int separator = value.indexOf(';');
      if (separator < 0) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_INVALID_SRID, "SRID prefix has no terminating semicolon");
      }
      String sridText = value.substring(5, separator).strip();
      if (sridText.isEmpty() || !isAsciiDigits(sridText)) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_INVALID_SRID, "SRID must be a non-negative decimal integer");
      }
      int srid;
      try {
        long parsed = Long.parseLong(sridText);
        if (parsed > Integer.MAX_VALUE) {
          throw new GeometryCodecException(
              GeometryCodecErrorCode.GC_INVALID_SRID, "SRID exceeds Integer.MAX_VALUE");
        }
        srid = (int) parsed;
      } catch (NumberFormatException e) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_INVALID_SRID, "SRID is not a valid integer", -1, e);
      }
      String body = value.substring(separator + 1).strip();
      if (body.isEmpty()) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_INVALID_WKT, "SRID prefix has no geometry body");
      }
      if (body.regionMatches(true, 0, "SRID=", 0, 5)) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_INVALID_SRID, "Nested or repeated SRID prefix");
      }
      return new EwktParts(body, srid);
    }
    return new EwktParts(value, null);
  }

  Geometry readGeometryBody(String body) throws GeometryCodecException {
    if (body == null || body.isBlank()) {
      return null;
    }
    return new CurveWktReader(factory, limits).read(body);
  }

  String writeGeometryBody(Geometry geometry) throws GeometryCodecException {
    return new CurveWktWriter().writeChecked(geometry);
  }

  private static boolean isAsciiDigits(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  private static GeometryFactory newGeometryFactory() {
    return new GeometryFactory(
        new org.locationtech.jts.geom.PrecisionModel(),
        0,
        PackedCoordinateSequenceFactory.DOUBLE_FACTORY);
  }

  record EwktParts(String body, Integer explicitSrid) {
    EwktParts {
      Objects.requireNonNull(body, "body");
    }
  }
}
