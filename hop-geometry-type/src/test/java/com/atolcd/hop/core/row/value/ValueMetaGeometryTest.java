package com.atolcd.hop.core.row.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import org.apache.hop.core.exception.HopEofException;
import org.apache.hop.core.exception.HopFileException;
import org.apache.hop.core.exception.HopPluginException;
import org.apache.hop.core.exception.HopValueException;
import org.apache.hop.core.row.value.ValueMetaString;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;

class ValueMetaGeometryTest {

  private static final WKTReader WKT_READER = new WKTReader();

  @Test
  void shouldRenderEwktIncludingSrid() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    Geometry geometry = WKT_READER.read("POINT(1 2)");
    geometry.setSRID(2056);

    String text = meta.getString(geometry);

    assertThat(text).isEqualTo("SRID=2056;POINT (1 2)");
  }

  @Test
  void shouldConvertEwktToGeometry() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    Geometry geometry = (Geometry) meta.convertData(new ValueMetaString("wkt"), "SRID=4326;LINESTRING(0 0,1 1)");

    assertThat(geometry.getSRID()).isEqualTo(4326);
    assertThat(geometry.toText()).isEqualTo("LINESTRING (0 0, 1 1)");
  }

  @Test
  void shouldCloneGeometryData() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    Geometry original = WKT_READER.read("POINT(5 6)");
    original.setSRID(3857);

    Geometry cloned = (Geometry) meta.cloneValueData(original);

    assertThat(cloned).isNotSameAs(original);
    assertThat(cloned.toText()).isEqualTo(original.toText());
    assertThat(cloned.getSRID()).isEqualTo(3857);
  }

  @Test
  void shouldRoundTripBinaryReadWrite() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    Geometry geometry = WKT_READER.read("POLYGON((0 0,0 1,1 1,1 0,0 0))");

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    meta.writeData(new DataOutputStream(baos), geometry);

    Object read = meta.readData(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));

    assertThat(read).isInstanceOf(Geometry.class);
    assertThat(((Geometry) read).toText()).isEqualTo(geometry.toText());
  }

  @Test
  void shouldRejectNumberConversion() {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");

    assertThatThrownBy(() -> meta.getNumber("POINT(0 0)"))
        .isInstanceOf(HopValueException.class)
        .hasMessageContaining("can't be converted to a number");
  }
}
