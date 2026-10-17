package com.atolcd.hop.gis.geometry.codec;

import com.atolcd.hop.gis.geometry.curve.CurveWkbReader;
import com.atolcd.hop.gis.geometry.curve.CurveWkbWriter;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;

/** WKB/EWKB facade over the extended shared curve readers/writers. */
public final class GeometryBinaryCodec {
  private final GeometryFactory factory;
  private final GeometryReadLimits limits;

  public GeometryBinaryCodec(GeometryReadLimits limits) {
    this(newGeometryFactory(), limits);
  }

  public GeometryBinaryCodec(GeometryFactory factory, GeometryReadLimits limits) {
    this.factory = factory == null ? newGeometryFactory() : factory;
    this.limits = limits == null ? GeometryReadLimits.defaults() : limits;
  }

  public Geometry read(byte[] bytes) throws GeometryCodecException {
    if (bytes == null) {
      return null;
    }
    if (bytes.length == 0) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_INVALID_WKB, "WKB payload is empty");
    }
    Geometry geometry = new CurveWkbReader(factory, limits).readChecked(bytes);
    GeometryLayoutSupport.validateRepresentable(geometry, limits);
    GeometrySridSupport.resolveConsistentSrid(geometry);
    return geometry;
  }

  public byte[] write(Geometry geometry, WkbWriteOptions options) throws GeometryCodecException {
    if (geometry == null) {
      return null;
    }
    if (options == null) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_TYPE_MISMATCH, "WKB write options must not be null");
    }
    try {
      options.validate();
    } catch (IllegalArgumentException e) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_TYPE_MISMATCH, e.getMessage(), -1, e);
    }
    GeometryLayoutSupport.validateRepresentable(geometry, limits);
    GeometrySridSupport.resolveConsistentSrid(geometry);
    return new CurveWkbWriter(options).write(geometry, options);
  }

  private static GeometryFactory newGeometryFactory() {
    return new GeometryFactory(
        new org.locationtech.jts.geom.PrecisionModel(),
        0,
        PackedCoordinateSequenceFactory.DOUBLE_FACTORY);
  }
}
