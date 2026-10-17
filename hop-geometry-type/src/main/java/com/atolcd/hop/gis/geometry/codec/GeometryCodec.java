package com.atolcd.hop.gis.geometry.codec;

import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;

/** Public facade for strict text, binary and hex geometry conversion. */
public final class GeometryCodec {
  private final GeometryReadLimits limits;
  private final GeometryFactory factory;
  private final GeometryTextCodec textCodec;
  private final GeometryBinaryCodec binaryCodec;

  public GeometryCodec(GeometryReadLimits limits) {
    this.limits = limits == null ? GeometryReadLimits.defaults() : limits;
    this.factory =
        new GeometryFactory(
            new org.locationtech.jts.geom.PrecisionModel(),
            0,
            PackedCoordinateSequenceFactory.DOUBLE_FACTORY);
    this.textCodec = new GeometryTextCodec(factory, this.limits);
    this.binaryCodec = new GeometryBinaryCodec(factory, this.limits);
  }

  public Geometry readText(String text) throws GeometryCodecException {
    return textCodec.read(text);
  }

  public Geometry readBinary(byte[] bytes) throws GeometryCodecException {
    return binaryCodec.read(bytes);
  }

  public Geometry readHex(String hex) throws GeometryCodecException {
    if (hex == null || hex.isBlank()) {
      return null;
    }
    return readBinary(GeometryHexCodec.decode(hex));
  }

  public String writeText(Geometry geometry, WktWriteOptions options) throws GeometryCodecException {
    return textCodec.write(geometry, options);
  }

  public byte[] writeBinary(Geometry geometry, WkbWriteOptions options)
      throws GeometryCodecException {
    return binaryCodec.write(geometry, options);
  }

  public String writeHex(Geometry geometry, WkbWriteOptions options) throws GeometryCodecException {
    return GeometryHexCodec.encode(writeBinary(geometry, options));
  }

  public Geometry copyExact(Geometry geometry) throws GeometryCodecException {
    if (geometry == null) {
      return null;
    }
    validateSupportedGeometry(geometry);
    return GeometrySridSupport.copyExact(geometry);
  }

  public void validateSupportedGeometry(Geometry geometry) throws GeometryCodecException {
    if (geometry == null) {
      return;
    }
    GeometryLayoutSupport.validateRepresentable(geometry, limits);
    GeometrySridSupport.resolveConsistentSrid(geometry);
  }

  public GeometryReadLimits limits() {
    return limits;
  }
}
