package com.atolcd.hop.gis.geometry.curve;

import java.math.BigDecimal;
import java.util.StringJoiner;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;

/** Writes the SQL/MM curve geometry types supported by the shared geometry runtime as WKT. */
public final class CurveWktWriter {

  public String write(Geometry geometry) {
    if (geometry instanceof CircularString circularString) {
      return "CIRCULARSTRING " + coordinateSequence(circularString.getControlPoints());
    }
    if (geometry instanceof CompoundCurve compoundCurve) {
      return "COMPOUNDCURVE " + compoundCurveBody(compoundCurve);
    }
    if (geometry instanceof CurvePolygon curvePolygon) {
      return "CURVEPOLYGON " + curvePolygonBody(curvePolygon);
    }
    if (geometry instanceof MultiCurve multiCurve) {
      return "MULTICURVE " + multiCurveBody(multiCurve);
    }
    if (geometry instanceof MultiSurface multiSurface) {
      return "MULTISURFACE " + multiSurfaceBody(multiSurface);
    }
    throw new IllegalArgumentException(
        "Not a supported curve geometry: " + geometry.getClass().getName());
  }

  private String compoundCurveBody(CompoundCurve curve) {
    StringJoiner members = new StringJoiner(", ", "(", ")");
    for (LineString component : curve.getComponents()) {
      members.add(curveMember(component));
    }
    return members.toString();
  }

  private String curvePolygonBody(CurvePolygon polygon) {
    StringJoiner rings = new StringJoiner(", ", "(", ")");
    for (LineString ring : polygon.getCurveRings()) {
      rings.add(curveMember(ring));
    }
    return rings.toString();
  }

  private String multiCurveBody(MultiCurve multiCurve) {
    StringJoiner curves = new StringJoiner(", ", "(", ")");
    for (LineString curve : multiCurve.getCurves()) {
      curves.add(curveMember(curve));
    }
    return curves.toString();
  }

  private String multiSurfaceBody(MultiSurface multiSurface) {
    StringJoiner surfaces = new StringJoiner(", ", "(", ")");
    for (Polygon surface : multiSurface.getSurfaces()) {
      if (surface instanceof CurvePolygon curvePolygon) {
        surfaces.add("CURVEPOLYGON " + curvePolygonBody(curvePolygon));
      } else {
        surfaces.add(polygonBody(surface));
      }
    }
    return surfaces.toString();
  }

  private String curveMember(LineString curve) {
    if (curve instanceof CircularString circularString) {
      return "CIRCULARSTRING " + coordinateSequence(circularString.getControlPoints());
    }
    if (curve instanceof CompoundCurve compoundCurve) {
      return "COMPOUNDCURVE " + compoundCurveBody(compoundCurve);
    }
    return coordinateSequence(curve.getCoordinates());
  }

  private String polygonBody(Polygon polygon) {
    if (polygon.isEmpty()) {
      return "EMPTY";
    }
    StringJoiner rings = new StringJoiner(", ", "(", ")");
    rings.add(coordinateSequence(polygon.getExteriorRing().getCoordinates()));
    for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
      rings.add(coordinateSequence(polygon.getInteriorRingN(i).getCoordinates()));
    }
    return rings.toString();
  }

  private String coordinateSequence(Coordinate[] coordinates) {
    StringJoiner values = new StringJoiner(", ", "(", ")");
    for (Coordinate coordinate : coordinates) {
      values.add(format(coordinate.x) + " " + format(coordinate.y));
    }
    return values.toString();
  }

  private String format(double value) {
    if (value == 0.0d) {
      return "0";
    }
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }
}
