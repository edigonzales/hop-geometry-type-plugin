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
      return "CIRCULARSTRING "
          + dimension(circularString)
          + coordinateSequence(circularString.getControlPoints());
    }
    if (geometry instanceof CompoundCurve compoundCurve) {
      return "COMPOUNDCURVE " + dimension(compoundCurve) + compoundCurveBody(compoundCurve);
    }
    if (geometry instanceof CurvePolygon curvePolygon) {
      return "CURVEPOLYGON " + dimension(curvePolygon) + curvePolygonBody(curvePolygon);
    }
    if (geometry instanceof MultiCurve multiCurve) {
      return "MULTICURVE " + dimension(multiCurve) + multiCurveBody(multiCurve);
    }
    if (geometry instanceof MultiSurface multiSurface) {
      return "MULTISURFACE " + dimension(multiSurface) + multiSurfaceBody(multiSurface);
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
        surfaces.add("CURVEPOLYGON " + dimension(curvePolygon) + curvePolygonBody(curvePolygon));
      } else {
        surfaces.add(polygonBody(surface));
      }
    }
    return surfaces.toString();
  }

  private String curveMember(LineString curve) {
    if (curve instanceof CircularString circularString) {
      return "CIRCULARSTRING "
          + dimension(circularString)
          + coordinateSequence(circularString.getControlPoints());
    }
    if (curve instanceof CompoundCurve compoundCurve) {
      return "COMPOUNDCURVE " + dimension(compoundCurve) + compoundCurveBody(compoundCurve);
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
    boolean z = java.util.Arrays.stream(coordinates).anyMatch(c -> !Double.isNaN(c.getZ()));
    boolean m =
        java.util.Arrays.stream(coordinates)
            .anyMatch(
                c ->
                    c instanceof org.locationtech.jts.geom.CoordinateXYM
                        || c instanceof org.locationtech.jts.geom.CoordinateXYZM);
    StringJoiner values = new StringJoiner(", ", "(", ")");
    for (Coordinate coordinate : coordinates) {
      values.add(
          format(coordinate.x)
              + " "
              + format(coordinate.y)
              + (z ? " " + format(coordinate.getZ()) : "")
              + (m ? " " + format(coordinate.getM()) : ""));
    }
    return values.toString();
  }

  private String dimension(Geometry geometry) {
    var points = geometry.getCoordinates();
    boolean z = java.util.Arrays.stream(points).anyMatch(c -> !Double.isNaN(c.getZ()));
    boolean m =
        java.util.Arrays.stream(points)
            .anyMatch(
                c ->
                    c instanceof org.locationtech.jts.geom.CoordinateXYM
                        || c instanceof org.locationtech.jts.geom.CoordinateXYZM);
    return z ? (m ? "ZM " : "Z ") : (m ? "M " : "");
  }

  private String format(double value) {
    if (Double.isNaN(value)) return "NaN";
    if (value == 0.0d) {
      return "0";
    }
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }
}
