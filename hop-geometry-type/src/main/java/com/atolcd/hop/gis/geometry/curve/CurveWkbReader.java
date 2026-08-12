package com.atolcd.hop.gis.geometry.curve;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

/** Minimal 2D WKB/EWKB reader for linear members and SQL/MM curve types 8 through 12. */
public final class CurveWkbReader {
  public static final int WKB_LINESTRING = 2;
  public static final int WKB_POLYGON = 3;
  public static final int WKB_CIRCULARSTRING = 8;
  public static final int WKB_COMPOUNDCURVE = 9;
  public static final int WKB_CURVEPOLYGON = 10;
  public static final int WKB_MULTICURVE = 11;
  public static final int WKB_MULTISURFACE = 12;

  static final int EWKB_Z = 0x80000000;
  static final int EWKB_M = 0x40000000;
  static final int EWKB_SRID = 0x20000000;
  static final int EWKB_TYPE_MASK = 0x1FFFFFFF;

  private final GeometryFactory factory;

  public CurveWkbReader() {
    this(new GeometryFactory());
  }

  public CurveWkbReader(GeometryFactory factory) {
    this.factory = factory;
  }

  public Geometry read(byte[] wkb) {
    if (wkb == null) {
      throw new IllegalArgumentException("WKB must not be null");
    }
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
    boolean hasZ = (rawType & EWKB_Z) != 0;
    boolean hasM = (rawType & EWKB_M) != 0;
    boolean hasSrid = (rawType & EWKB_SRID) != 0;
    int type = rawType & EWKB_TYPE_MASK;

    if (hasZ || hasM) {
      throw new IllegalArgumentException("Curve WKB with Z/M ordinates is not supported yet");
    }
    if (type >= 1000) {
      throw new IllegalArgumentException("SQL/MM curve WKB with Z/M ordinates is not supported yet");
    }

    int srid = hasSrid ? cursor.readInt(order) : 0;
    Geometry geometry =
        switch (type) {
          case WKB_LINESTRING -> factory.createLineString(readCoordinates(cursor, order));
          case WKB_POLYGON -> readPolygon(cursor, order);
          case WKB_CIRCULARSTRING -> new CircularString(readCoordinates(cursor, order), factory);
          case WKB_COMPOUNDCURVE -> readCompoundCurve(cursor, order);
          case WKB_CURVEPOLYGON -> readCurvePolygon(cursor, order);
          case WKB_MULTICURVE -> readMultiCurve(cursor, order);
          case WKB_MULTISURFACE -> readMultiSurface(cursor, order);
          default -> throw new IllegalArgumentException("Unsupported WKB geometry type: " + type);
        };
    if (hasSrid) {
      geometry.setSRID(srid);
    }
    return geometry;
  }

  private Polygon readPolygon(Cursor cursor, ByteOrder order) {
    int count = readCount(cursor, order, "POLYGON ring");
    if (count == 0) {
      return factory.createPolygon();
    }

    LinearRing shell = factory.createLinearRing(readCoordinates(cursor, order));
    LinearRing[] holes = new LinearRing[count - 1];
    for (int i = 0; i < holes.length; i++) {
      holes[i] = factory.createLinearRing(readCoordinates(cursor, order));
    }
    return factory.createPolygon(shell, holes);
  }

  private CompoundCurve readCompoundCurve(Cursor cursor, ByteOrder order) {
    int count = readCount(cursor, order, "COMPOUNDCURVE component");
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
    int count = readCount(cursor, order, "CURVEPOLYGON ring");
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

  private MultiCurve readMultiCurve(Cursor cursor, ByteOrder order) {
    int count = readCount(cursor, order, "MULTICURVE component");
    List<LineString> curves = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Geometry curve = readGeometry(cursor);
      if (!(curve instanceof LineString line)) {
        throw new IllegalArgumentException("MULTICURVE component must be a curve/line");
      }
      curves.add(line);
    }
    return new MultiCurve(curves, factory);
  }

  private MultiSurface readMultiSurface(Cursor cursor, ByteOrder order) {
    int count = readCount(cursor, order, "MULTISURFACE component");
    List<Polygon> surfaces = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      Geometry surface = readGeometry(cursor);
      if (!(surface instanceof Polygon polygon)) {
        throw new IllegalArgumentException("MULTISURFACE component must be a polygon/surface");
      }
      surfaces.add(polygon);
    }
    return new MultiSurface(surfaces, factory);
  }

  private Coordinate[] readCoordinates(Cursor cursor, ByteOrder order) {
    int count = readCount(cursor, order, "coordinate");
    Coordinate[] coordinates = new Coordinate[count];
    for (int i = 0; i < count; i++) {
      coordinates[i] = new Coordinate(cursor.readDouble(order), cursor.readDouble(order));
    }
    return coordinates;
  }

  private int readCount(Cursor cursor, ByteOrder order, String valueName) {
    int count = cursor.readInt(order);
    if (count < 0) {
      throw new IllegalArgumentException("Negative " + valueName + " count in WKB: " + count);
    }
    return count;
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
