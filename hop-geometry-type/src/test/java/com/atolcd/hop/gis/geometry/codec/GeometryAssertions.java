package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CompoundCurve;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/** Structural comparison helper. Topology or text equality alone is deliberately not used. */
final class GeometryAssertions {
  private GeometryAssertions() {}

  static void assertGeometryPreserved(Geometry expected, Geometry actual) {
    assertGeometryPreserved(expected, actual, true);
  }

  static void assertGeometryPreserved(Geometry expected, Geometry actual, boolean compareSrid) {
    assertThat(actual).isNotNull();
    assertThat(actual.getClass()).isEqualTo(expected.getClass());
    if (compareSrid) {
      assertThat(actual.getSRID()).isEqualTo(expected.getSRID());
    }
    if (expected instanceof CircularString expectedCurve) {
      CircularString actualCurve = (CircularString) actual;
      assertSequence(expectedCurve.getControlPointSequence(), actualCurve.getControlPointSequence());
      return;
    }
    if (expected instanceof CompoundCurve expectedCurve) {
      CompoundCurve actualCurve = (CompoundCurve) actual;
      assertThat(actualCurve.getComponents()).hasSameSizeAs(expectedCurve.getComponents());
      for (int i = 0; i < expectedCurve.getComponents().size(); i++) {
        assertGeometryPreserved(
            expectedCurve.getComponents().get(i), actualCurve.getComponents().get(i), compareSrid);
      }
      return;
    }
    if (expected instanceof CurvePolygon expectedPolygon) {
      CurvePolygon actualPolygon = (CurvePolygon) actual;
      assertThat(actualPolygon.getCurveRings()).hasSameSizeAs(expectedPolygon.getCurveRings());
      for (int i = 0; i < expectedPolygon.getCurveRings().size(); i++) {
        assertGeometryPreserved(
            expectedPolygon.getCurveRings().get(i), actualPolygon.getCurveRings().get(i), compareSrid);
      }
      return;
    }
    if (expected instanceof Point expectedPoint) {
      assertSequence(expectedPoint.getCoordinateSequence(), ((Point) actual).getCoordinateSequence());
      return;
    }
    if (expected instanceof LineString expectedLine) {
      assertSequence(expectedLine.getCoordinateSequence(), ((LineString) actual).getCoordinateSequence());
      return;
    }
    if (expected instanceof Polygon expectedPolygon) {
      assertPolygonRings(expectedPolygon, (Polygon) actual);
      return;
    }
    if (expected instanceof GeometryCollection expectedCollection) {
      GeometryCollection actualCollection = (GeometryCollection) actual;
      assertThat(actualCollection.getNumGeometries())
          .isEqualTo(expectedCollection.getNumGeometries());
      for (int i = 0; i < expectedCollection.getNumGeometries(); i++) {
        assertGeometryPreserved(
            expectedCollection.getGeometryN(i), actualCollection.getGeometryN(i), compareSrid);
      }
      return;
    }
    throw new AssertionError("Unsupported geometry in assertion helper: " + expected.getClass());
  }

  private static void assertSequence(CoordinateSequence expected, CoordinateSequence actual) {
    assertThat(actual.getDimension()).isEqualTo(expected.getDimension());
    assertThat(actual.getMeasures()).isEqualTo(expected.getMeasures());
    assertThat(actual.size()).isEqualTo(expected.size());
    for (int i = 0; i < expected.size(); i++) {
      for (int ordinate = 0; ordinate < expected.getDimension(); ordinate++) {
        assertEquals(
            expected.getOrdinate(i, ordinate),
            actual.getOrdinate(i, ordinate),
            "coordinate " + i + " ordinate " + ordinate);
      }
    }
  }

  private static void assertPolygonRings(Polygon expected, Polygon actual) {
    assertSequence(expected.getExteriorRing().getCoordinateSequence(), actual.getExteriorRing().getCoordinateSequence());
    assertThat(actual.getNumInteriorRing()).isEqualTo(expected.getNumInteriorRing());
    for (int i = 0; i < expected.getNumInteriorRing(); i++) {
      assertSequence(
          expected.getInteriorRingN(i).getCoordinateSequence(),
          actual.getInteriorRingN(i).getCoordinateSequence());
    }
  }
}
