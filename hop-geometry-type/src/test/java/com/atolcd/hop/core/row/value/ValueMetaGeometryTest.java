package com.atolcd.hop.core.row.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import com.atolcd.hop.gis.geometry.curve.CurveWkbReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.HexFormat;
import org.apache.hop.core.exception.HopFileException;
import org.apache.hop.core.exception.HopValueException;
import org.apache.hop.core.row.value.ValueMetaString;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;

class ValueMetaGeometryTest {

  private static final WKTReader WKT_READER = new WKTReader();
  private static final byte[] CURVE_POLYGON_WKB =
      HexFormat.of()
          .parseHex(
              "010A00000001000000010800000005000000"
                  + "00000000000000000000000000000000"
                  + "00000000000010400000000000000000"
                  + "00000000000010400000000000001040"
                  + "00000000000000000000000000001040"
                  + "00000000000000000000000000000000");

  @Test
  void shouldRenderEwktIncludingSrid() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    Geometry geometry = WKT_READER.read("POINT(1 2)");
    geometry.setSRID(2056);

    String text = meta.getString(geometry);

    assertThat(text).isEqualTo("SRID=2056;POINT (1 2)");
  }

  @Test
  void shouldRenderCurvePolygonEwktWithoutLinearizing() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    CurvePolygon geometry = curvePolygon();
    geometry.setSRID(2056);

    String text = meta.getString(geometry);

    assertThat(text)
        .isEqualTo(
            "SRID=2056;CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0))");
  }

  @Test
  void shouldConvertEwktToGeometry() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    Geometry geometry =
        (Geometry)
            meta.convertData(
                new ValueMetaString("wkt"), "SRID=4326;LINESTRING(0 0,1 1)");

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
  void shouldCloneCurveGeometryWithoutLosingCurveType() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    CurvePolygon original = curvePolygon();
    original.setSRID(2056);

    Geometry cloned = (Geometry) meta.cloneValueData(original);

    assertThat(cloned).isInstanceOf(CurvePolygon.class).isNotSameAs(original);
    assertThat(cloned.getSRID()).isEqualTo(2056);
    assertCircularRingControlPoints((CurvePolygon) cloned);
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
  void shouldPreserveSridInStandardGeometryBinaryRoundTrip() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    Geometry geometry = WKT_READER.read("POINT(7 8)");
    geometry.setSRID(2056);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    meta.writeData(new DataOutputStream(baos), geometry);

    Geometry read =
        (Geometry) meta.readData(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));

    assertThat(read.getSRID()).isEqualTo(2056);
    assertThat(read.toText()).isEqualTo(geometry.toText());
  }

  @Test
  void shouldRoundTripCurveBinaryWithoutStroking() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    CurvePolygon geometry = curvePolygon();
    geometry.setSRID(2056);

    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    meta.writeData(new DataOutputStream(baos), geometry);

    Object read = meta.readData(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));

    assertThat(read).isInstanceOf(CurvePolygon.class);
    assertThat(((CurvePolygon) read).getSRID()).isEqualTo(2056);
    assertCircularRingControlPoints((CurvePolygon) read);
  }

  @Test
  void shouldFailCleanlyOnMalformedWkbInsteadOfFallingThroughStorageCases() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    DataOutputStream out = new DataOutputStream(baos);
    out.writeBoolean(false);
    out.writeInt(4);
    out.write(new byte[] {1, 2, 3, 4});

    assertThatThrownBy(
            () -> meta.readData(new DataInputStream(new ByteArrayInputStream(baos.toByteArray()))))
        .isInstanceOf(HopFileException.class)
        .hasMessageContaining("Unable to parse geometry WKB");
  }

  @Test
  void shouldRejectNumberConversion() {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");

    assertThatThrownBy(() -> meta.getNumber("POINT(0 0)"))
        .isInstanceOf(HopValueException.class)
        .hasMessageContaining("can't be converted to a number");
  }

  private static CurvePolygon curvePolygon() {
    return (CurvePolygon) new CurveWkbReader().read(CURVE_POLYGON_WKB);
  }

  private static void assertCircularRingControlPoints(CurvePolygon polygon) {
    assertThat(polygon.getCurveRings()).hasSize(1);
    assertThat(polygon.getCurveRings().get(0)).isInstanceOf(CircularString.class);

    CircularString ring = (CircularString) polygon.getCurveRings().get(0);
    assertThat(ring.getControlPoints()).hasSize(5);
    assertThat(ring.getControlPoints()[0].x).isEqualTo(0.0);
    assertThat(ring.getControlPoints()[0].y).isEqualTo(0.0);
    assertThat(ring.getControlPoints()[1].x).isEqualTo(4.0);
    assertThat(ring.getControlPoints()[1].y).isEqualTo(0.0);
    assertThat(ring.getControlPoints()[2].x).isEqualTo(4.0);
    assertThat(ring.getControlPoints()[2].y).isEqualTo(4.0);
    assertThat(ring.getControlPoints()[3].x).isEqualTo(0.0);
    assertThat(ring.getControlPoints()[3].y).isEqualTo(4.0);
    assertThat(ring.getControlPoints()[4].x).isEqualTo(0.0);
    assertThat(ring.getControlPoints()[4].y).isEqualTo(0.0);
  }
}
