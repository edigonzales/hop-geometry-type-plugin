package com.atolcd.hop.gis.geometry.curve;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;

/** Minimal 2D ISO WKB writer for LINESTRING and SQL/MM curve types 8, 9 and 10. */
public final class CurveWkbWriter {
  private final ByteOrder byteOrder;

  public CurveWkbWriter() {
    this(ByteOrder.LITTLE_ENDIAN);
  }

  public CurveWkbWriter(ByteOrder byteOrder) {
    this.byteOrder = byteOrder;
  }

  public byte[] write(Geometry geometry) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    writeGeometry(out, geometry);
    return out.toByteArray();
  }

  private void writeGeometry(ByteArrayOutputStream out, Geometry geometry) {
    writeByteOrder(out);
    if (geometry instanceof CurvePolygon polygon) {
      writeInt(out, CurveWkbReader.WKB_CURVEPOLYGON);
      writeCurvePolygon(out, polygon);
    } else if (geometry instanceof CompoundCurve compoundCurve) {
      writeInt(out, CurveWkbReader.WKB_COMPOUNDCURVE);
      writeCompoundCurve(out, compoundCurve);
    } else if (geometry instanceof CircularString circularString) {
      writeInt(out, CurveWkbReader.WKB_CIRCULARSTRING);
      writeCoordinates(out, circularString.getControlPoints());
    } else if (geometry instanceof LineString lineString) {
      writeInt(out, CurveWkbReader.WKB_LINESTRING);
      writeCoordinates(out, lineString.getCoordinates());
    } else {
      throw new IllegalArgumentException("Unsupported geometry for curve PoC: " + geometry.getGeometryType());
    }
  }

  private void writeCompoundCurve(ByteArrayOutputStream out, CompoundCurve curve) {
    List<LineString> components = curve.getComponents();
    writeInt(out, components.size());
    for (LineString component : components) {
      writeGeometry(out, component);
    }
  }

  private void writeCurvePolygon(ByteArrayOutputStream out, CurvePolygon polygon) {
    List<LineString> rings = polygon.getCurveRings();
    writeInt(out, rings.size());
    for (LineString ring : rings) {
      writeGeometry(out, ring);
    }
  }

  private void writeCoordinates(ByteArrayOutputStream out, Coordinate[] coordinates) {
    writeInt(out, coordinates.length);
    for (Coordinate coordinate : coordinates) {
      writeDouble(out, coordinate.x);
      writeDouble(out, coordinate.y);
    }
  }

  private void writeByteOrder(ByteArrayOutputStream out) {
    out.write(byteOrder == ByteOrder.LITTLE_ENDIAN ? 1 : 0);
  }

  private void writeInt(ByteArrayOutputStream out, int value) {
    out.writeBytes(ByteBuffer.allocate(Integer.BYTES).order(byteOrder).putInt(value).array());
  }

  private void writeDouble(ByteArrayOutputStream out, double value) {
    out.writeBytes(ByteBuffer.allocate(Double.BYTES).order(byteOrder).putDouble(value).array());
  }
}
