package com.atolcd.hop.gis.geometry.curve;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;

class LinearizationTest {
  @Test
  void reverseAndMeasuresArePreserved() {
    Coordinate a = new CoordinateXYZM(2600005, 1200000, 1, 10),
        m = new CoordinateXYZM(2600003, 1200004, 4, 20),
        b = new CoordinateXYZM(2599995, 1200000, 8, 30);
    var forward = new ArcSegment(a, m, b).linearize(0.001);
    var backward = new ArcSegment(b, m, a).linearize(0.001);
    assertThat(forward).hasSameSizeAs(backward);
    for (int i = 0; i < forward.length; i++) {
      Coordinate p = forward[i], q = backward[backward.length - 1 - i];
      assertThat(p.x).isEqualTo(q.x);
      assertThat(p.y).isEqualTo(q.y);
      assertThat(p.getM()).isEqualTo(q.getM());
      assertThat(Math.hypot(p.x - 2600000, p.y - 1200000)).isCloseTo(5, within(1e-7));
    }
    assertThat(
            java.util.Arrays.stream(forward)
                .anyMatch(p -> p.equals2D(m) && p.getZ() == 4 && p.getM() == 20))
        .isTrue();
  }

  @Test
  void linearizationDoesNotInventMeasures() {
    var f = new GeometryFactory();
    var c =
        new CircularString(
            new Coordinate[] {new Coordinate(1, 0), new Coordinate(0, 1), new Coordinate(-1, 0)},
            f);
    var result = (LineString) CurveGeometrySupport.linearize(c, 0.001);
    assertThat(result).isNotInstanceOf(CircularString.class);
    assertThat(result.getCoordinateSequence().hasM()).isFalse();
    assertThatThrownBy(() -> CurveGeometrySupport.linearize(c, Double.NaN))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void copyAndReverseKeepExactCurves() {
    var f = new GeometryFactory();
    var c =
        new CircularString(
            new Coordinate[] {
              new CoordinateXYZM(1, 0, 1, 2),
              new CoordinateXYZM(0, 1, 3, 4),
              new CoordinateXYZM(-1, 0, 5, 6)
            },
            f);
    assertThat(c.copy()).isInstanceOf(CircularString.class);
    assertThat(c.reverse()).isInstanceOf(CircularString.class);
    var reversed = (CircularString) c.reverse();
    assertThat(reversed.getControlPoints()[0].getM()).isEqualTo(6);
    var a = CurveGeometrySupport.linearize(c, 0.01).getCoordinates();
    var b = CurveGeometrySupport.linearize(reversed, 0.01).getCoordinates();
    for (int j = 0; j < a.length; j++) assertThat(a[j].equals3D(b[b.length - 1 - j])).isTrue();
  }

  @Test
  void reversingAFullCircleChangesOrientation() {
    var f = new GeometryFactory();
    var c =
        new CircularString(
            new Coordinate[] {new Coordinate(5, 0), new Coordinate(-5, 0), new Coordinate(5, 0)},
            f);
    assertThat(org.locationtech.jts.algorithm.Orientation.isCCW(c.getCoordinates())).isTrue();
    assertThat(org.locationtech.jts.algorithm.Orientation.isCCW(c.reverse().getCoordinates()))
        .isFalse();
  }
}
