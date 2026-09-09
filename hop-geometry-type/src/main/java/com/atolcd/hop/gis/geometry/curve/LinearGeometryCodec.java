package com.atolcd.hop.gis.geometry.curve;

import java.util.EnumSet;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.io.*;

/** Shared linear codec retaining declared Z/M sequences, including missing ordinates. */
public final class LinearGeometryCodec {
  private LinearGeometryCodec() {}

  public static EnumSet<Ordinate> ordinates(Geometry geometry) {
    var result = EnumSet.of(Ordinate.X, Ordinate.Y);
    geometry.apply(
        new GeometryComponentFilter() {
          public void filter(Geometry g) {
            CoordinateSequence s =
                g instanceof Point p
                    ? p.getCoordinateSequence()
                    : g instanceof LineString l ? l.getCoordinateSequence() : null;
            if (s != null) {
              if (s.hasZ()) result.add(Ordinate.Z);
              if (s.hasM()) result.add(Ordinate.M);
            }
          }
        });
    return result;
  }

  public static byte[] writeWkb(Geometry geometry) {
    var o = ordinates(geometry);
    var writer = new WKBWriter(o.size(), ByteOrderValues.BIG_ENDIAN, geometry.getSRID() != 0);
    writer.setOutputOrdinates(o);
    return writer.write(geometry);
  }

  public static Geometry readWkb(byte[] bytes) throws ParseException {
    Geometry geometry = new WKBReader().read(bytes);
    var b =
        java.nio.ByteBuffer.wrap(bytes)
            .order(
                bytes[0] == 0 ? java.nio.ByteOrder.BIG_ENDIAN : java.nio.ByteOrder.LITTLE_ENDIAN);
    int type = b.getInt(1), iso = (type & 0x1fffffff) / 1000;
    boolean z = (type & 0x80000000) != 0 || iso == 1 || iso == 3,
        m = (type & 0x40000000) != 0 || iso == 2 || iso == 3;
    int dimensions = 2 + (z ? 1 : 0) + (m ? 1 : 0);
    var editor = new org.locationtech.jts.geom.util.GeometryEditor(geometry.getFactory());
    Geometry restored =
        editor.edit(
            geometry,
            new org.locationtech.jts.geom.util.GeometryEditor.CoordinateSequenceOperation() {
              public CoordinateSequence edit(CoordinateSequence sequence, Geometry component) {
                return sequence.size() == 0
                    ? org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory.DOUBLE_FACTORY
                        .create(0, dimensions, m ? 1 : 0)
                    : sequence;
              }
            });
    restored.setSRID(geometry.getSRID());
    return restored;
  }

  public static String writeWkt(Geometry geometry) {
    var o = ordinates(geometry);
    var writer = new WKTWriter(o.size());
    writer.setOutputOrdinates(o);
    return writer.write(geometry);
  }
}
