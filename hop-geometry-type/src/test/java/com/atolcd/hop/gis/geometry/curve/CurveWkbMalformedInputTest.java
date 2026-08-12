package com.atolcd.hop.gis.geometry.curve;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.jupiter.api.Test;

class CurveWkbMalformedInputTest {

  @Test
  void rejectsNegativeCoordinateCount() {
    byte[] wkb =
        ByteBuffer.allocate(1 + Integer.BYTES + Integer.BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .put((byte) 1)
            .putInt(CurveWkbReader.WKB_CIRCULARSTRING)
            .putInt(-1)
            .array();

    assertThatThrownBy(() -> new CurveWkbReader().read(wkb))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Negative coordinate count");
  }
}
