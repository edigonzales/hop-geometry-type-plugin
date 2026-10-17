package com.atolcd.hop.gis.geometry.curve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

class ArcSegmentCountTest {
  @Test
  void countMatchesLinearizeForSeveralArcsAndFullCircle() {
    Coordinate[][] cases = {
      {new Coordinate(1, 0), new Coordinate(0, 1), new Coordinate(-1, 0)},
      {new Coordinate(0, 0), new Coordinate(2, 0), new Coordinate(0, 0)},
      {new Coordinate(2600000, 1200000), new Coordinate(2600003, 1200004), new Coordinate(2599995, 1200000)},
      {new Coordinate(5, 5), new Coordinate(5, 5), new Coordinate(5, 5)}
    };
    for (Coordinate[] c : cases) {
      ArcSegment arc = new ArcSegment(c[0], c[1], c[2]);
      long expected = arc.linearize(0.001).length;
      assertThat(arc.linearizedCoordinateCount(0.001, Long.MAX_VALUE)).isEqualTo(expected);
    }
  }

  @Test
  void countStopsAtTheBudget() {
    ArcSegment arc =
        new ArcSegment(new Coordinate(1, 0), new Coordinate(0, 1), new Coordinate(-1, 0));
    assertThat(arc.linearizedCoordinateCount(0.001, 2)).isEqualTo(3);
    assertThat(arc.linearizedCoordinateCount(0.001, 1)).isEqualTo(2);
    assertThatThrownBy(() -> arc.linearizedCoordinateCount(Double.NaN, 100))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
