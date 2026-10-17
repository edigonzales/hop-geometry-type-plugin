package com.atolcd.hop.core.row.value;

import static org.assertj.core.api.Assertions.assertThat;

import com.atolcd.hop.gis.geometry.curve.CurveGeometrySupport;
import com.atolcd.hop.gis.geometry.curve.CurveWkbReader;
import com.atolcd.hop.gis.utils.GeometryUtils;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.HexFormat;
import org.apache.hop.core.row.value.ValueMetaPlugin;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.io.WKTReader;

/** Freezes the pre-codec representations documented in docs/implementation/legacy-baseline.md. */
class LegacyGeometryContractTest {
  private static final String CURVE_POLYGON_HEX =
      "010A00000001000000010800000005000000"
          + "00000000000000000000000000000000"
          + "00000000000010400000000000000000"
          + "00000000000010400000000000001040"
          + "00000000000000000000000000001040"
          + "00000000000000000000000000000000";

  @Test
  void standardStringRepresentationAndSridPrefixRemainUnchanged() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    Geometry xy = new WKTReader().read("POINT (1 2)");
    Geometry z = new WKTReader().read("POINT Z (1 2 3)");
    Geometry srid = new WKTReader().read("POINT (1 2)");
    srid.setSRID(2056);
    CurvePolygonHolder curve = curve();

    assertThat(meta.getString(xy)).isEqualTo("POINT (1 2)");
    assertThat(meta.getString(z)).isEqualTo("POINT Z(1 2 3)");
    assertThat(meta.getString(srid)).isEqualTo("SRID=2056;POINT (1 2)");
    assertThat(meta.getString(curve.geometry))
        .isEqualTo("SRID=2056;CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0))");
  }

  @Test
  void legacyLinearAndCurveWkbOutputsRemainByteIdentical() throws Exception {
    Geometry xy = new WKTReader().read("POINT (1 2)");
    assertThat(hex(CurveGeometrySupport.writeWkb(xy)))
        .isEqualTo("00800000013ff000000000000040000000000000007ff8000000000000");

    Geometry srid = new WKTReader().read("POINT (1 2)");
    srid.setSRID(2056);
    assertThat(hex(CurveGeometrySupport.writeWkb(srid)))
        .isEqualTo("00a0000001000008083ff000000000000040000000000000007ff8000000000000");

    assertThat(hex(CurveGeometrySupport.writeWkb(curve().geometry)))
        .isEqualTo(
            "010a00002008080000010000000108000000050000000000000000000000000000000000000000000000000010400000000000000000000000000000104000000000000010400000000000000000000000000000104000000000000000000000000000000000");
  }

  @Test
  void hopStreamFramingRemainsUnchanged() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    Geometry xy = new WKTReader().read("POINT (1 2)");
    assertThat(writeData(meta, xy))
        .isEqualTo(
            "000000001d00800000013ff000000000000040000000000000007ff8000000000000");

    Geometry srid = new WKTReader().read("POINT (1 2)");
    srid.setSRID(2056);
    assertThat(writeData(meta, srid))
        .isEqualTo(
            "000000002100a0000001000008083ff000000000000040000000000000007ff8000000000000");

    assertThat(writeData(meta, curve().geometry))
        .isEqualTo(
            "0000000066010a00002008080000010000000108000000050000000000000000000000000000000000000000000000000010400000000000000000000000000000104000000000000010400000000000000000000000000000104000000000000000000000000000000000");
  }

  @Test
  void legacyEwktEmptyStillBecomesNullWhileCodecPreservesEmpty() throws Exception {
    assertThat(GeometryUtils.getGeometryFromEWKT("POINT EMPTY")).isNull();
    Geometry ewkt = GeometryUtils.getGeometryFromEWKT("SRID=2056;POINT(1 2)");
    assertThat(ewkt).isNotNull();
    assertThat(ewkt.getSRID()).isEqualTo(2056);
  }

  @Test
  void stableGeometryTypeAndClassLoaderGroupRemainUnchanged() {
    assertThat(ValueMetaGeometry.TYPE_GEOMETRY).isEqualTo(43663879);
    ValueMetaPlugin annotation = ValueMetaGeometry.class.getAnnotation(ValueMetaPlugin.class);
    assertThat(annotation).isNotNull();
    assertThat(annotation.id()).isEqualTo("43663879");
    assertThat(annotation.classLoaderGroup()).isEqualTo("sogeo-geometry");
  }

  private static CurvePolygonHolder curve() {
    Geometry geometry = new CurveWkbReader().read(HexFormat.of().parseHex(CURVE_POLYGON_HEX));
    geometry.setSRID(2056);
    return new CurvePolygonHolder(geometry);
  }

  private static String writeData(ValueMetaGeometry meta, Object value) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    meta.writeData(new DataOutputStream(out), value);
    return hex(out.toByteArray());
  }

  private static String hex(byte[] value) {
    return HexFormat.of().formatHex(value);
  }

  private record CurvePolygonHolder(Geometry geometry) {}
}
