package com.atolcd.hop.gis.geometry.curve;

import java.util.List;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

/** SQL/MM CURVEPOLYGON represented as a JTS Polygon plus exact curve rings. */
public final class CurvePolygon extends Polygon {
  private final List<LineString> curveRings;

  public CurvePolygon(List<? extends LineString> curveRings, GeometryFactory factory) {
    super(shell(curveRings, factory), holes(curveRings, factory), factory);
    if (curveRings.isEmpty()) {
      throw new IllegalArgumentException("CURVEPOLYGON requires at least one ring");
    }
    this.curveRings = List.copyOf(curveRings);
  }

  public List<LineString> getCurveRings() {
    return curveRings;
  }

  private static LinearRing shell(List<? extends LineString> rings, GeometryFactory factory) {
    if (rings.isEmpty()) {
      throw new IllegalArgumentException("CURVEPOLYGON requires at least one ring");
    }
    return factory.createLinearRing(rings.get(0).getCoordinates());
  }

  private static LinearRing[] holes(List<? extends LineString> rings, GeometryFactory factory) {
    LinearRing[] holes = new LinearRing[Math.max(0, rings.size() - 1)];
    for (int i = 1; i < rings.size(); i++) {
      holes[i - 1] = factory.createLinearRing(rings.get(i).getCoordinates());
    }
    return holes;
  }
}
