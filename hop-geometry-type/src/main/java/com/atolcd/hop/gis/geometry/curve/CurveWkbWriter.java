package com.atolcd.hop.gis.geometry.curve;

import com.atolcd.hop.gis.geometry.codec.CoordinateLayout;
import com.atolcd.hop.gis.geometry.codec.GeometryCodecErrorCode;
import com.atolcd.hop.gis.geometry.codec.GeometryCodecException;
import com.atolcd.hop.gis.geometry.codec.GeometryLayoutSupport;
import com.atolcd.hop.gis.geometry.codec.GeometrySridSupport;
import com.atolcd.hop.gis.geometry.codec.WkbDialect;
import com.atolcd.hop.gis.geometry.codec.WkbWriteOptions;
import java.io.ByteArrayOutputStream;
import java.nio.ByteOrder;
import java.util.List;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/**
 * WKB/EWKB writer for types 1 through 12. The legacy constructors keep their historical output
 * profile (little endian, EWKB for known SRIDs); explicit options use the shared write contract.
 */
public final class CurveWkbWriter {
  private final WkbWriteOptions options;
  private final ByteOrder legacyByteOrder;

  public CurveWkbWriter() {
    this(ByteOrder.LITTLE_ENDIAN);
  }

  public CurveWkbWriter(ByteOrder byteOrder) {
    this.options = null;
    this.legacyByteOrder = byteOrder == null ? ByteOrder.LITTLE_ENDIAN : byteOrder;
  }

  public CurveWkbWriter(WkbWriteOptions options) {
    this.options = options;
    this.legacyByteOrder =
        options == null ? ByteOrder.LITTLE_ENDIAN : options.byteOrder().toByteOrder();
  }

  /** Legacy entry point: little endian and EWKB for known SRIDs. */
  public byte[] write(Geometry geometry) {
    try {
      WkbWriteOptions legacy =
          options != null
              ? options
              : new WkbWriteOptions(
                  WkbDialect.EWKB,
                  com.atolcd.hop.gis.geometry.codec.GeometryByteOrder.fromByteOrder(
                      legacyByteOrder),
                  com.atolcd.hop.gis.geometry.codec.SridEncoding.IF_KNOWN);
      return writeChecked(geometry, legacy);
    } catch (GeometryCodecException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  /** Explicit option path used by the shared binary codec. */
  public byte[] write(Geometry geometry, WkbWriteOptions requested)
      throws GeometryCodecException {
    if (geometry == null) {
      return null;
    }
    return writeChecked(geometry, requested);
  }

  private byte[] writeChecked(Geometry geometry, WkbWriteOptions requested)
      throws GeometryCodecException {
    if (geometry == null) {
      return null;
    }
    if (requested == null) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_TYPE_MISMATCH, "WKB write options must not be null");
    }
    requested.validate();
    GeometryLayoutSupport.validateRepresentable(geometry);
    int srid = GeometrySridSupport.resolveConsistentSrid(geometry);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    WriteContext context =
        new WriteContext(
            requested, requested.byteOrder().toByteOrder(), requested.dialect(), srid);
    writeGeometry(out, geometry, context, true);
    return out.toByteArray();
  }

  private void writeGeometry(
      ByteArrayOutputStream out, Geometry geometry, WriteContext context, boolean root)
      throws GeometryCodecException {
    CoordinateLayout layout = headerLayout(geometry);
    int baseType = baseType(geometry);
    boolean writeSrid =
        root
            && context.dialect == WkbDialect.EWKB
            && switch (context.options.sridEncoding()) {
              case OMIT -> false;
              case IF_KNOWN -> context.srid > 0;
              case ALWAYS -> true;
            };
    int type = baseType;
    if (context.dialect == WkbDialect.ISO_SQL_MM) {
      type += layout.isoOffset();
    } else {
      type |= layout.ewkbFlags();
    }
    if (writeSrid) {
      type |= CurveWkbReader.EWKB_SRID;
    }
    writeByteOrder(out, context.byteOrder);
    writeInt(out, type, context.byteOrder);
    if (writeSrid) {
      writeInt(out, context.srid, context.byteOrder);
    }
    writePayload(out, geometry, layout, context);
  }

  private void writePayload(
      ByteArrayOutputStream out,
      Geometry geometry,
      CoordinateLayout layout,
      WriteContext context)
      throws GeometryCodecException {
    if (geometry instanceof CircularString circularString) {
      writeSequence(out, circularString.getControlPointSequence(), layout, context);
      return;
    }
    if (geometry instanceof CompoundCurve compoundCurve) {
      List<LineString> components = compoundCurve.getComponents();
      writeInt(out, components.size(), context.byteOrder);
      for (LineString component : components) {
        writeGeometry(out, component, context, false);
      }
      return;
    }
    if (geometry instanceof CurvePolygon curvePolygon) {
      List<LineString> rings = curvePolygon.getCurveRings();
      writeInt(out, rings.size(), context.byteOrder);
      for (LineString ring : rings) {
        writeGeometry(out, ring, context, false);
      }
      return;
    }
    if (geometry instanceof MultiCurve multiCurve) {
      List<LineString> curves = multiCurve.getCurves();
      writeInt(out, curves.size(), context.byteOrder);
      for (LineString curve : curves) {
        writeGeometry(out, curve, context, false);
      }
      return;
    }
    if (geometry instanceof MultiSurface multiSurface) {
      List<Polygon> surfaces = multiSurface.getSurfaces();
      writeInt(out, surfaces.size(), context.byteOrder);
      for (Polygon surface : surfaces) {
        writeGeometry(out, surface, context, false);
      }
      return;
    }
    if (geometry instanceof Point point) {
      writePoint(out, point, layout, context);
      return;
    }
    if (geometry instanceof LinearRing linearRing) {
      writeSequence(out, linearRing.getCoordinateSequence(), layout, context);
      return;
    }
    if (geometry instanceof LineString lineString) {
      writeSequence(out, lineString.getCoordinateSequence(), layout, context);
      return;
    }
    if (geometry instanceof Polygon polygon) {
      writePolygon(out, polygon, layout, context);
      return;
    }
    if (geometry instanceof MultiPoint multiPoint) {
      writeInt(out, multiPoint.getNumGeometries(), context.byteOrder);
      for (int i = 0; i < multiPoint.getNumGeometries(); i++) {
        writeGeometry(out, multiPoint.getGeometryN(i), context, false);
      }
      return;
    }
    if (geometry instanceof MultiLineString multiLineString) {
      writeInt(out, multiLineString.getNumGeometries(), context.byteOrder);
      for (int i = 0; i < multiLineString.getNumGeometries(); i++) {
        writeGeometry(out, multiLineString.getGeometryN(i), context, false);
      }
      return;
    }
    if (geometry instanceof MultiPolygon multiPolygon) {
      writeInt(out, multiPolygon.getNumGeometries(), context.byteOrder);
      for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
        writeGeometry(out, multiPolygon.getGeometryN(i), context, false);
      }
      return;
    }
    if (geometry instanceof GeometryCollection collection) {
      writeInt(out, collection.getNumGeometries(), context.byteOrder);
      for (int i = 0; i < collection.getNumGeometries(); i++) {
        writeGeometry(out, collection.getGeometryN(i), context, false);
      }
      return;
    }
    throw new GeometryCodecException(
        GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY,
        "Unsupported geometry class: " + geometry.getClass().getName());
  }

  private void writePoint(
      ByteArrayOutputStream out,
      Point point,
      CoordinateLayout layout,
      WriteContext context)
      throws GeometryCodecException {
    CoordinateSequence sequence = point.getCoordinateSequence();
    if (sequence.size() == 0) {
      for (int i = 0; i < layout.dimension(); i++) {
        writeDouble(out, Double.NaN, context.byteOrder);
      }
      return;
    }
    if (sequence.size() != 1) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_TYPE_MISMATCH, "POINT must contain at most one coordinate");
    }
    writeCoordinate(out, sequence, 0, layout, context);
  }

  private void writePolygon(
      ByteArrayOutputStream out,
      Polygon polygon,
      CoordinateLayout layout,
      WriteContext context)
      throws GeometryCodecException {
    if (polygon.isEmpty()) {
      writeInt(out, 0, context.byteOrder);
      return;
    }
    writeInt(out, polygon.getNumInteriorRing() + 1, context.byteOrder);
    writeSequence(out, polygon.getExteriorRing().getCoordinateSequence(), layout, context);
    for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
      writeSequence(out, polygon.getInteriorRingN(i).getCoordinateSequence(), layout, context);
    }
  }

  private void writeSequence(
      ByteArrayOutputStream out,
      CoordinateSequence sequence,
      CoordinateLayout layout,
      WriteContext context)
      throws GeometryCodecException {
    writeInt(out, sequence.size(), context.byteOrder);
    for (int i = 0; i < sequence.size(); i++) {
      writeCoordinate(out, sequence, i, layout, context);
    }
  }

  private void writeCoordinate(
      ByteArrayOutputStream out,
      CoordinateSequence sequence,
      int index,
      CoordinateLayout layout,
      WriteContext context) {
    writeDouble(out, sequence.getX(index), context.byteOrder);
    writeDouble(out, sequence.getY(index), context.byteOrder);
    if (layout.hasZ()) {
      writeDouble(out, sequence.getZ(index), context.byteOrder);
    }
    if (layout.hasM()) {
      writeDouble(out, sequence.getM(index), context.byteOrder);
    }
  }

  private CoordinateLayout headerLayout(Geometry geometry) throws GeometryCodecException {
    if (geometry instanceof GeometryCollection) {
      return GeometryLayoutSupport.aggregateLayout(geometry);
    }
    return GeometryLayoutSupport.primitiveLayout(geometry);
  }

  private int baseType(Geometry geometry) throws GeometryCodecException {
    if (geometry instanceof CircularString) {
      return CurveWkbReader.WKB_CIRCULARSTRING;
    }
    if (geometry instanceof CompoundCurve) {
      return CurveWkbReader.WKB_COMPOUNDCURVE;
    }
    if (geometry instanceof CurvePolygon) {
      return CurveWkbReader.WKB_CURVEPOLYGON;
    }
    if (geometry instanceof MultiCurve) {
      return CurveWkbReader.WKB_MULTICURVE;
    }
    if (geometry instanceof MultiSurface) {
      return CurveWkbReader.WKB_MULTISURFACE;
    }
    if (geometry instanceof Point) {
      return CurveWkbReader.WKB_POINT;
    }
    if (geometry instanceof LinearRing || geometry instanceof LineString) {
      return CurveWkbReader.WKB_LINESTRING;
    }
    if (geometry instanceof Polygon) {
      return CurveWkbReader.WKB_POLYGON;
    }
    if (geometry instanceof MultiPoint) {
      return CurveWkbReader.WKB_MULTIPOINT;
    }
    if (geometry instanceof MultiLineString) {
      return CurveWkbReader.WKB_MULTILINESTRING;
    }
    if (geometry instanceof MultiPolygon) {
      return CurveWkbReader.WKB_MULTIPOLYGON;
    }
    if (geometry instanceof GeometryCollection) {
      return CurveWkbReader.WKB_GEOMETRYCOLLECTION;
    }
    throw new GeometryCodecException(
        GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY,
        "Unsupported geometry class: " + geometry.getClass().getName());
  }

  private void writeByteOrder(ByteArrayOutputStream out, ByteOrder order) {
    out.write(order == ByteOrder.LITTLE_ENDIAN ? 1 : 0);
  }

  private void writeInt(ByteArrayOutputStream out, int value, ByteOrder order) {
    if (order == ByteOrder.BIG_ENDIAN) {
      out.write((value >>> 24) & 0xff);
      out.write((value >>> 16) & 0xff);
      out.write((value >>> 8) & 0xff);
      out.write(value & 0xff);
    } else {
      out.write(value & 0xff);
      out.write((value >>> 8) & 0xff);
      out.write((value >>> 16) & 0xff);
      out.write((value >>> 24) & 0xff);
    }
  }

  private void writeDouble(ByteArrayOutputStream out, double value, ByteOrder order) {
    long bits = Double.doubleToLongBits(value);
    if (order == ByteOrder.BIG_ENDIAN) {
      for (int shift = 56; shift >= 0; shift -= 8) {
        out.write((int) (bits >>> shift) & 0xff);
      }
    } else {
      for (int shift = 0; shift <= 56; shift += 8) {
        out.write((int) (bits >>> shift) & 0xff);
      }
    }
  }

  private record WriteContext(
      WkbWriteOptions options,
      ByteOrder byteOrder,
      WkbDialect dialect,
      int srid) {}
}
