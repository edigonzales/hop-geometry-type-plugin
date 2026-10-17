package com.atolcd.hop.gis.geometry.curve;

import com.atolcd.hop.gis.geometry.codec.CoordinateLayout;
import com.atolcd.hop.gis.geometry.codec.GeometryCodecErrorCode;
import com.atolcd.hop.gis.geometry.codec.GeometryCodecException;
import com.atolcd.hop.gis.geometry.codec.GeometryLayoutSupport;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
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

/** Typed, layout-preserving WKT writer for all supported linear and curved geometry types. */
public final class CurveWktWriter {

  public String write(Geometry geometry) {
    try {
      return writeChecked(geometry);
    } catch (GeometryCodecException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  public String writeChecked(Geometry geometry) throws GeometryCodecException {
    if (geometry == null) {
      return null;
    }
    if (!GeometryLayoutSupport.isSupportedGeometry(geometry)) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY,
          "Unsupported geometry class: " + geometry.getClass().getName());
    }
    if (geometry instanceof CircularString circularString) {
      return circularStringText(circularString);
    }
    if (geometry instanceof CompoundCurve compoundCurve) {
      return compoundCurveText(compoundCurve);
    }
    if (geometry instanceof CurvePolygon curvePolygon) {
      return curvePolygonText(curvePolygon);
    }
    if (geometry instanceof MultiCurve multiCurve) {
      return multiCurveText(multiCurve);
    }
    if (geometry instanceof MultiSurface multiSurface) {
      return multiSurfaceText(multiSurface);
    }
    if (geometry instanceof Point point) {
      return pointText(point);
    }
    if (geometry instanceof LinearRing linearRing) {
      return lineStringText(linearRing.getCoordinateSequence());
    }
    if (geometry instanceof LineString lineString) {
      return lineStringText(lineString.getCoordinateSequence());
    }
    if (geometry instanceof Polygon polygon) {
      return polygonText(polygon);
    }
    if (geometry instanceof MultiPoint multiPoint) {
      return multiPointText(multiPoint);
    }
    if (geometry instanceof MultiLineString multiLineString) {
      return multiLineStringText(multiLineString);
    }
    if (geometry instanceof MultiPolygon multiPolygon) {
      return multiPolygonText(multiPolygon);
    }
    if (geometry instanceof GeometryCollection collection) {
      return collectionText(collection);
    }
    throw new GeometryCodecException(
        GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY,
        "Unsupported geometry class: " + geometry.getClass().getName());
  }

  private String circularStringText(CircularString curve) throws GeometryCodecException {
    CoordinateSequence controls = curve.getControlPointSequence();
    CoordinateLayout layout = GeometryLayoutSupport.layoutOf(controls);
    if (controls.size() == 0) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE,
          "Empty CIRCULARSTRING is not representable");
    }
    return tagged("CIRCULARSTRING", layout) + coordinateSequence(controls, layout);
  }

  private String compoundCurveText(CompoundCurve compoundCurve) throws GeometryCodecException {
    CoordinateLayout layout = GeometryLayoutSupport.primitiveLayout(compoundCurve);
    if (compoundCurve.getComponents().isEmpty()) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE,
          "Empty COMPOUNDCURVE is not representable");
    }
    StringJoiner members = new StringJoiner(", ", "(", ")");
    for (LineString component : compoundCurve.getComponents()) {
      members.add(curveMemberText(component));
    }
    return tagged("COMPOUNDCURVE", layout) + members;
  }

  private String curvePolygonText(CurvePolygon polygon) throws GeometryCodecException {
    CoordinateLayout layout = GeometryLayoutSupport.primitiveLayout(polygon);
    if (polygon.getCurveRings().isEmpty()) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE,
          "Empty CURVEPOLYGON is not representable");
    }
    StringJoiner rings = new StringJoiner(", ", "(", ")");
    for (LineString ring : polygon.getCurveRings()) {
      rings.add(curveMemberText(ring));
    }
    return tagged("CURVEPOLYGON", layout) + rings;
  }

  private String multiCurveText(MultiCurve multiCurve) throws GeometryCodecException {
    if (multiCurve.getCurves().isEmpty()) {
      return "MULTICURVE EMPTY";
    }
    CoordinateLayout common = commonLayout(multiCurve.getCurves());
    StringJoiner members = new StringJoiner(", ", "(", ")");
    if (common != null) {
      for (LineString curve : multiCurve.getCurves()) {
        members.add(curveMemberText(curve));
      }
      return tagged("MULTICURVE", common) + members;
    }
    for (LineString curve : multiCurve.getCurves()) {
      CoordinateLayout layout = GeometryLayoutSupport.primitiveLayout(curve);
      if (curve instanceof CircularString || curve instanceof CompoundCurve) {
        members.add(curveMemberText(curve));
      } else {
        members.add(
            tagged("LINESTRING", layout)
                + coordinateSequence(curve.getCoordinateSequence(), layout));
      }
    }
    return "MULTICURVE " + members;
  }

  private String multiSurfaceText(MultiSurface multiSurface) throws GeometryCodecException {
    if (multiSurface.getSurfaces().isEmpty()) {
      return "MULTISURFACE EMPTY";
    }
    CoordinateLayout common = commonLayout(multiSurface.getSurfaces());
    StringJoiner members = new StringJoiner(", ", "(", ")");
    if (common != null) {
      for (Polygon surface : multiSurface.getSurfaces()) {
        if (surface instanceof CurvePolygon curvePolygon) {
          members.add(curvePolygonText(curvePolygon));
        } else {
          members.add(polygonBody(surface, common));
        }
      }
      return tagged("MULTISURFACE", common) + members;
    }
    for (Polygon surface : multiSurface.getSurfaces()) {
      CoordinateLayout layout = GeometryLayoutSupport.primitiveLayout(surface);
      if (surface instanceof CurvePolygon curvePolygon) {
        members.add(curvePolygonText(curvePolygon));
      } else {
        members.add(tagged("POLYGON", layout) + polygonBody(surface, layout));
      }
    }
    return "MULTISURFACE " + members;
  }

  private String curveMemberText(LineString curve) throws GeometryCodecException {
    if (curve instanceof CircularString circularString) {
      return circularStringText(circularString);
    }
    if (curve instanceof CompoundCurve compoundCurve) {
      return compoundCurveText(compoundCurve);
    }
    return coordinateSequence(
        curve.getCoordinateSequence(), GeometryLayoutSupport.primitiveLayout(curve));
  }

  private String pointText(Point point) throws GeometryCodecException {
    CoordinateSequence sequence = point.getCoordinateSequence();
    CoordinateLayout layout = GeometryLayoutSupport.layoutOf(sequence);
    if (point.isEmpty()) {
      return tagged("POINT", layout) + "EMPTY";
    }
    return tagged("POINT", layout) + coordinateSequence(sequence, layout);
  }

  private String lineStringText(CoordinateSequence sequence) throws GeometryCodecException {
    CoordinateLayout layout = GeometryLayoutSupport.lineStringLayout(sequence);
    if (sequence.size() == 0) {
      return tagged("LINESTRING", layout) + "EMPTY";
    }
    return tagged("LINESTRING", layout) + coordinateSequence(sequence, layout);
  }

  private String polygonText(Polygon polygon) throws GeometryCodecException {
    CoordinateLayout layout = GeometryLayoutSupport.primitiveLayout(polygon);
    if (polygon.isEmpty()) {
      return tagged("POLYGON", layout) + "EMPTY";
    }
    return tagged("POLYGON", layout) + polygonBody(polygon, layout);
  }

  private String multiPointText(MultiPoint multiPoint) throws GeometryCodecException {
    if (multiPoint.getNumGeometries() == 0) {
      return "MULTIPOINT EMPTY";
    }
    List<Point> points = new ArrayList<>();
    for (int i = 0; i < multiPoint.getNumGeometries(); i++) {
      points.add((Point) multiPoint.getGeometryN(i));
    }
    CoordinateLayout layout = uniformLayout(points, "MULTIPOINT members");
    StringJoiner members = new StringJoiner(", ", "(", ")");
    for (Point point : points) {
      if (point.isEmpty()) {
        members.add("EMPTY");
      } else {
        members.add(coordinateSequence(point.getCoordinateSequence(), layout));
      }
    }
    return tagged("MULTIPOINT", layout) + members;
  }

  private String multiLineStringText(MultiLineString multiLineString) throws GeometryCodecException {
    if (multiLineString.getNumGeometries() == 0) {
      return "MULTILINESTRING EMPTY";
    }
    List<LineString> lines = new ArrayList<>();
    for (int i = 0; i < multiLineString.getNumGeometries(); i++) {
      lines.add((LineString) multiLineString.getGeometryN(i));
    }
    CoordinateLayout layout = uniformLayout(lines, "MULTILINESTRING members");
    StringJoiner members = new StringJoiner(", ", "(", ")");
    for (LineString line : lines) {
      members.add(coordinateSequence(line.getCoordinateSequence(), layout));
    }
    return tagged("MULTILINESTRING", layout) + members;
  }

  private String multiPolygonText(MultiPolygon multiPolygon) throws GeometryCodecException {
    if (multiPolygon.getNumGeometries() == 0) {
      return "MULTIPOLYGON EMPTY";
    }
    List<Polygon> polygons = new ArrayList<>();
    for (int i = 0; i < multiPolygon.getNumGeometries(); i++) {
      polygons.add((Polygon) multiPolygon.getGeometryN(i));
    }
    CoordinateLayout layout = uniformLayout(polygons, "MULTIPOLYGON members");
    StringJoiner members = new StringJoiner(", ", "(", ")");
    for (Polygon polygon : polygons) {
      members.add(polygonBody(polygon, layout));
    }
    return tagged("MULTIPOLYGON", layout) + members;
  }

  private String collectionText(GeometryCollection collection) throws GeometryCodecException {
    if (collection.getNumGeometries() == 0) {
      return "GEOMETRYCOLLECTION EMPTY";
    }
    StringJoiner members = new StringJoiner(", ", "(", ")");
    for (int i = 0; i < collection.getNumGeometries(); i++) {
      members.add(writeChecked(collection.getGeometryN(i)));
    }
    return "GEOMETRYCOLLECTION " + members;
  }

  private String polygonBody(Polygon polygon, CoordinateLayout layout)
      throws GeometryCodecException {
    if (polygon.isEmpty()) {
      return "EMPTY";
    }
    StringJoiner rings = new StringJoiner(", ", "(", ")");
    rings.add(
        coordinateSequence(polygon.getExteriorRing().getCoordinateSequence(), layout));
    for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
      rings.add(
          coordinateSequence(polygon.getInteriorRingN(i).getCoordinateSequence(), layout));
    }
    return rings.toString();
  }

  private String coordinateSequence(CoordinateSequence sequence, CoordinateLayout layout) {
    StringJoiner values = new StringJoiner(", ", "(", ")");
    for (int i = 0; i < sequence.size(); i++) {
      StringBuilder coordinate = new StringBuilder();
      coordinate.append(format(sequence.getX(i))).append(' ').append(format(sequence.getY(i)));
      if (layout.hasZ()) {
        coordinate.append(' ').append(format(sequence.getZ(i)));
      }
      if (layout.hasM()) {
        coordinate.append(' ').append(format(sequence.getM(i)));
      }
      values.add(coordinate.toString());
    }
    return values.toString();
  }

  private CoordinateLayout commonLayout(List<? extends Geometry> geometries)
      throws GeometryCodecException {
    CoordinateLayout layout = null;
    for (Geometry geometry : geometries) {
      CoordinateLayout next = GeometryLayoutSupport.primitiveLayout(geometry);
      if (layout == null) {
        layout = next;
      } else if (layout != next) {
        return null;
      }
    }
    return layout;
  }

  private CoordinateLayout uniformLayout(List<? extends Geometry> geometries, String role)
      throws GeometryCodecException {
    CoordinateLayout layout = null;
    for (Geometry geometry : geometries) {
      CoordinateLayout next = GeometryLayoutSupport.primitiveLayout(geometry);
      if (layout == null) {
        layout = next;
      } else if (layout != next) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT,
            "Inconsistent coordinate layouts in " + role);
      }
    }
    return layout == null ? CoordinateLayout.XY : layout;
  }

  private String tagged(String type, CoordinateLayout layout) {
    return layout == CoordinateLayout.XY ? type + " " : type + " " + word(layout) + " ";
  }

  private static String word(CoordinateLayout layout) {
    return switch (layout) {
      case XY -> "";
      case XYZ -> "Z";
      case XYM -> "M";
      case XYZM -> "ZM";
    };
  }

  private String format(double value) {
    if (Double.isNaN(value)) {
      return "NaN";
    }
    if (value == 0.0d) {
      return "0";
    }
    return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
  }
}
