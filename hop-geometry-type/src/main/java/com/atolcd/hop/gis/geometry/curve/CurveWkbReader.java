package com.atolcd.hop.gis.geometry.curve;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/** Minimal 2D ISO WKB reader for LINESTRING and SQL/MM curve types 8, 9 and 10. */
public final class CurveWkbReader {
  public static final int WKB_LINESTRING = 2;
  public static final int WKB_CIRCULARSTRING = 8;
  public static final int WKB_COMPOUNDCURVE = 9;
  public static final int WKB_CURVEPOLYGON = 10;

  private final GeometryFactory factory;

  public CurveWkbReader() {
    this(new GeometryFactory());
  }

  public CurveWkbReader(GeometryFactory factory) {
    this.factory = factory;
  }

  public Geometry read(byte[] wkb) {
    Cursor cursor = new Cursor(wkb);
    Geometry geometry = readGeometry(cursor);
    if (cursor.position != wkb.length) {
      throw new IllegalArgumentException("Trailing bytes after WKB geometry");
    }
    return geometry;
  }

  private Geometry readGeometry(Cursor cursor) {
    ByteOrder order = cursor.readOrder();
    int rawType = cursor.readInt(order);
    if ((rawType & 0xE0000000) != 0 || rawType >= 1000) {
      throw new IllegalArgumentException("PoC supports 2D ISO WKB without EWKB flags only");
    }
    return switch (rawType) {
      case WKB_LINESTRING -> factory.createLineString(readCoordinates(cursor, order));
      case WKB_CIRCULARSTRING -> new CircularString(readCoordinates(cursor, order), factory);
      case WKB_COMPOUNDCURVE -> readCompoundCurve(cursor, order);
      case WKB_CURVEPOLYGON -> readCurvePolygon(cursor, order);
      default -> throw new IllegalArgumentException("Unsupported WKB geometry type: " + rawType);
    };
  }

  private CompoundCurve readCompoundCurve(Cursor cursor, ByteOrder order) {
    int count = cursor.readInt(order);
    List<LineString> components = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Geometry component = readGeometry(cursor);
      if (!(component instanceof LineString line)) {
        throw new IllegalArgumentException("COMPOUNDCURVE component must be a curve/line");
      }
      components.add(line);
    }
    return new CompoundCurve(components, factory);
  }

  private CurvePolygon readCurvePolygon(Cursor cursor, ByteOrder order) {
    int count = cursor.readInt(order);
    List<LineString> rings = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Geometry ring = readGeometry(cursor);
      if (!(ring instanceof LineString line)) {
        throw new IllegalArgumentException("CURVEPOLYGON ring must be a curve/line");
      }
      if (!line.isClosed()) {
        throw new IllegalArgumentException("CURVEPOLYGON ring must be closed");
      }
      rings.add(line);
    }
    return new CurvePolygon(rings, factory);
  }

  private Coordinate[] readCoordinates(Cursor cursor, ByteOrder order) {
    int count = cursor.readInt(order);
    Coordinate[] coordinates = new Coordinate[count];
    for (int i = 0; i < count; i++) {
      coordinates[i] = new Coordinate(cursor.readDouble(order), cursor.readDouble(order));
    }
    return coordinates;
  }

  private static final class Cursor {
    private final byte[] data;
    private int position;

    private Cursor(byte[] data) {
      this.data = data;
    }

    private ByteOrder readOrder() {
      ensure(1);
      int marker = data[position++] & 0xff;
      return switch (marker) {
        case 0 -> ByteOrder.BIG_ENDIAN;
        case 1 -> ByteOrder.LITTLE_ENDIAN;
        default -> throw new IllegalArgumentException("Invalid WKB byte order: " + marker);
      };
    }

    private int readInt(ByteOrder order) {
      ensure(Integer.BYTES);
      int value = ByteBuffer.wrap(data, position, Integer.BYTES).order(order).getInt();
      position += Integer.BYTES;
      return value;
    }

    private double readDouble(ByteOrder order) {
      ensure(Double.BYTES);
      double value = ByteBuffer.wrap(data, position, Double.BYTES).order(order).getDouble();
      position += Double.BYTES;
      return value;
    }

    private void ensure(int size) {
      if (position + size > data.length) {
        throw new IllegalArgumentException("Unexpected end of WKB");
      }
    }
  }
}
