package com.atolcd.hop.gis.geometry.curve;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

/** Minimal 2D WKB/EWKB writer for linear members and SQL/MM curve types 8 through 12. */
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
    writeGeometry(out, geometry, true);
    return out.toByteArray();
  }

  private void writeGeometry(ByteArrayOutputStream out, Geometry geometry, boolean includeSrid) {
    writeByteOrder(out);
    boolean writeSrid = includeSrid && geometry.getSRID() != 0;
    int type;
    if (geometry instanceof MultiSurface) {
      type = CurveWkbReader.WKB_MULTISURFACE;
    } else if (geometry instanceof MultiCurve) {
      type = CurveWkbReader.WKB_MULTICURVE;
    } else if (geometry instanceof CurvePolygon) {
      type = CurveWkbReader.WKB_CURVEPOLYGON;
    } else if (geometry instanceof CompoundCurve) {
      type = CurveWkbReader.WKB_COMPOUNDCURVE;
    } else if (geometry instanceof CircularString) {
      type = CurveWkbReader.WKB_CIRCULARSTRING;
    } else if (geometry instanceof Polygon) {
      type = CurveWkbReader.WKB_POLYGON;
    } else if (geometry instanceof LineString) {
      type = CurveWkbReader.WKB_LINESTRING;
    } else {
      throw new IllegalArgumentException(
          "Unsupported geometry for curve WKB: " + geometry.getGeometryType());
    }

    writeInt(out, writeSrid ? type | CurveWkbReader.EWKB_SRID : type);
    if (writeSrid) {
      writeInt(out, geometry.getSRID());
    }

    if (geometry instanceof MultiSurface multiSurface) {
      writeMultiSurface(out, multiSurface);
    } else if (geometry instanceof MultiCurve multiCurve) {
      writeMultiCurve(out, multiCurve);
    } else if (geometry instanceof CurvePolygon polygon) {
      writeCurvePolygon(out, polygon);
    } else if (geometry instanceof CompoundCurve compoundCurve) {
      writeCompoundCurve(out, compoundCurve);
    } else if (geometry instanceof CircularString circularString) {
      writeCoordinates(out, circularString.getControlPoints());
    } else if (geometry instanceof Polygon polygon) {
      writePolygon(out, polygon);
    } else {
      writeCoordinates(out, ((LineString) geometry).getCoordinates());
    }
  }

  private void writeCompoundCurve(ByteArrayOutputStream out, CompoundCurve curve) {
    List<LineString> components = curve.getComponents();
    writeInt(out, components.size());
    for (LineString component : components) {
      writeGeometry(out, component, false);
    }
  }

  private void writeCurvePolygon(ByteArrayOutputStream out, CurvePolygon polygon) {
    List<LineString> rings = polygon.getCurveRings();
    writeInt(out, rings.size());
    for (LineString ring : rings) {
      writeGeometry(out, ring, false);
    }
  }

  private void writeMultiCurve(ByteArrayOutputStream out, MultiCurve multiCurve) {
    List<LineString> curves = multiCurve.getCurves();
    writeInt(out, curves.size());
    for (LineString curve : curves) {
      writeGeometry(out, curve, false);
    }
  }

  private void writeMultiSurface(ByteArrayOutputStream out, MultiSurface multiSurface) {
    List<Polygon> surfaces = multiSurface.getSurfaces();
    writeInt(out, surfaces.size());
    for (Polygon surface : surfaces) {
      writeGeometry(out, surface, false);
    }
  }

  private void writePolygon(ByteArrayOutputStream out, Polygon polygon) {
    if (polygon.isEmpty()) {
      writeInt(out, 0);
      return;
    }

    writeInt(out, polygon.getNumInteriorRing() + 1);
    writeCoordinates(out, polygon.getExteriorRing().getCoordinates());
    for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
      writeCoordinates(out, polygon.getInteriorRingN(i).getCoordinates());
    }
  }

  private void writeCoordinates(ByteArrayOutputStream out, Coordinate[] coordinates) {
    writeInt(out, coordinates.length);
    for (Coordinate coordinate : coordinates) {
      if (!Double.isNaN(coordinate.getZ()) || !Double.isNaN(coordinate.getM())) {
        throw new IllegalArgumentException("Curve WKB with Z/M ordinates is not supported yet");
      }
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
