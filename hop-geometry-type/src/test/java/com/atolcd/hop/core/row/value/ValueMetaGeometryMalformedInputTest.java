package com.atolcd.hop.core.row.value;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import org.apache.hop.core.exception.HopFileException;
import org.junit.jupiter.api.Test;

class ValueMetaGeometryMalformedInputTest {

  @Test
  void rejectsNegativeGeometryWkbSize() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(baos);
    out.writeBoolean(false);
    out.writeInt(-1);

    assertThatThrownBy(
            () -> meta.readData(new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))))
        .isInstanceOf(HopFileException.class)
        .hasMessageContaining("Negative geometry WKB size");
  }
}
