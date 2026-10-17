package com.atolcd.hop.core.row.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atolcd.hop.gis.geometry.codec.CoordinateLayout;
import com.atolcd.hop.gis.geometry.codec.GeometryCodec;
import com.atolcd.hop.gis.geometry.codec.GeometryLayoutSupport;
import com.atolcd.hop.gis.geometry.codec.GeometryReadLimits;
import java.util.HexFormat;
import org.apache.hop.core.exception.HopPluginException;
import org.apache.hop.core.exception.HopValueException;
import org.apache.hop.core.plugins.PluginRegistry;
import org.apache.hop.core.row.IValueMeta;
import org.apache.hop.core.row.value.ValueMetaBinary;
import org.apache.hop.core.row.value.ValueMetaFactory;
import org.apache.hop.core.row.value.ValueMetaPlugin;
import org.apache.hop.core.row.value.ValueMetaPluginType;
import org.apache.hop.core.row.value.ValueMetaString;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;

class ValueMetaGeometryConversionTest {
  private static final String POINT_XY_LE =
      "0101000000000000000000F03F0000000000000040";
  private static final String POINT_XYZ_LE =
      "01E9030000000000000000F03F00000000000000400000000000000840";
  private static final String POINT_XYM_LE =
      "01D1070000000000000000F03F00000000000000400000000000001040";
  private static final String POINT_XYZM_LE =
      "01B90B0000000000000000F03F000000000000004000000000000008400000000000001040";
  private static final String POINT_EWKB_BE_SRID =
      "0020000001000008083FF00000000000004000000000000000";
  private static final String CURVE_POLYGON_LE =
      "010A000000010000000108000000050000000000000000000000000000000000000000000000000010400000000000000000000000000000104000000000000010400000000000000000000000000000104000000000000000000000000000000000";

  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());

  @BeforeAll
  static void registerGeometryPlugin() throws Exception {
    org.apache.hop.core.HopEnvironment.init();
    PluginRegistry registry = PluginRegistry.getInstance();
    String id = String.valueOf(ValueMetaGeometry.TYPE_GEOMETRY);
    if (registry.findPluginWithId(ValueMetaPluginType.class, id) == null) {
      registry.registerPluginClass(
          ValueMetaGeometry.class.getName(), ValueMetaPluginType.class, ValueMetaPlugin.class);
    }
  }

  @Test
  void t01LegacyStringConversionIsProtectedAndEmptyStaysNull() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    ValueMetaString source = new ValueMetaString("wkt");

    Geometry point = (Geometry) meta.convertData(source, "POINT (1 2)");
    assertThat(point.getGeometryType()).isEqualTo("Point");
    assertThat(point.getSRID()).isZero();

    Geometry polygon =
        (Geometry) meta.convertData(source, "POLYGON ((0 0, 4 0, 4 4, 4 0, 0 0), (1 1, 2 1, 2 2, 1 1))");
    assertThat(polygon.getGeometryType()).isEqualTo("Polygon");

    Geometry ewkt = (Geometry) meta.convertData(source, "SRID=2056;POINT (1 2)");
    assertThat(ewkt.getSRID()).isEqualTo(2056);

    assertThat(meta.convertData(source, null)).isNull();
    assertThat(meta.convertData(source, "   ")).isNull();
    assertThat(meta.convertData(source, "POINT EMPTY")).isNull();

    Geometry decodedEmpty = codec.readText("POINT EMPTY");
    assertThat(decodedEmpty).isNotNull();
    assertThat(decodedEmpty.isEmpty()).isTrue();
    assertThat(meta.getString(point)).isEqualTo("POINT (1 2)");
    assertThat(meta.getString(ewkt)).isEqualTo("SRID=2056;POINT (1 2)");
  }

  @Test
  void t02BinaryToGeometryThroughValueMetaFactoryAndRealTargetMeta() throws Exception {
    IValueMeta target =
        ValueMetaFactory.createValueMeta("geom", ValueMetaGeometry.TYPE_GEOMETRY);
    assertThat(target).isInstanceOf(ValueMetaGeometry.class);
    ValueMetaBinary source = new ValueMetaBinary("payload");

    assertBinaryConverted(target, source, POINT_XY_LE, CoordinateLayout.XY, 0);
    assertBinaryConverted(target, source, POINT_XYZ_LE, CoordinateLayout.XYZ, 0);
    assertBinaryConverted(target, source, POINT_XYM_LE, CoordinateLayout.XYM, 0);
    assertBinaryConverted(target, source, POINT_XYZM_LE, CoordinateLayout.XYZM, 0);
    assertBinaryConverted(target, source, POINT_EWKB_BE_SRID, CoordinateLayout.XY, 2056);

    Geometry curvePolygon =
        (Geometry) target.convertData(source, HexFormat.of().parseHex(CURVE_POLYGON_LE));
    assertThat(curvePolygon).isInstanceOf(com.atolcd.hop.gis.geometry.curve.CurvePolygon.class);

    assertThat(target.convertData(source, null)).isNull();
    assertThatThrownBy(() -> target.convertData(source, new byte[0]))
        .isInstanceOf(HopValueException.class);
    assertThatThrownBy(
            () -> target.convertData(source, "POINT (1 2)".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .isInstanceOf(HopValueException.class);
  }

  @Test
  void t03GeometryToBinaryThroughTheActualValueMetaBinaryHook() throws Exception {
    ValueMetaGeometry geometryMeta = new ValueMetaGeometry("geom");
    ValueMetaBinary target = new ValueMetaBinary("payload");
    Point point = (Point) codec.readText("POINT (1 2)");
    point.setSRID(2056);

    byte[] payload = (byte[]) target.convertData(geometryMeta, point);
    assertThat(payload).isNotEmpty();
    assertThat(HexFormat.of().formatHex(payload).toUpperCase())
        .isEqualTo(POINT_EWKB_BE_SRID);
    Geometry decoded = codec.readBinary(payload);
    assertThat(decoded.getSRID()).isEqualTo(2056);
    assertThat(decoded.getGeometryType()).isEqualTo("Point");

    assertThat(target.convertData(geometryMeta, null)).isNull();

    Geometry curve =
        codec.readText("CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0))");
    byte[] curvePayload = (byte[]) target.convertData(geometryMeta, curve);
    assertThat(codec.readBinary(curvePayload)).isInstanceOf(com.atolcd.hop.gis.geometry.curve.CurvePolygon.class);
  }

  @Test
  void t03IndexedGeometryStorageIsNormalizedForBinaryOutput() throws Exception {
    Point point = (Point) codec.readText("POINT (1 2)");
    point.setSRID(2056);
    ValueMetaGeometry indexed = new ValueMetaGeometry("geom");
    indexed.setStorageType(IValueMeta.STORAGE_TYPE_INDEXED);
    indexed.setIndex(new Object[] {point});

    byte[] payload =
        (byte[]) new ValueMetaBinary("payload").convertData(indexed, Integer.valueOf(0));
    assertThat(HexFormat.of().formatHex(payload).toUpperCase())
        .isEqualTo(POINT_EWKB_BE_SRID);
  }

  @Test
  void t03UnsupportedLazyGeometryStorageIsRejectedClearly() throws Exception {
    Point point = (Point) codec.readText("POINT (1 2)");
    ValueMetaGeometry lazy = new ValueMetaGeometry("geom");
    lazy.setStorageType(IValueMeta.STORAGE_TYPE_BINARY_STRING);
    assertThatThrownBy(() -> lazy.getBinary(new byte[] {1, 2, 3}))
        .isInstanceOf(HopValueException.class)
        .hasMessageContaining("lazy binary-string");
  }

  @Test
  void t04HexIsAcceptedInStringConversionAndWktModeStillRejectsIt() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    ValueMetaString source = new ValueMetaString("text");
    for (String text :
        new String[] {
          POINT_EWKB_BE_SRID,
          POINT_EWKB_BE_SRID.toLowerCase(),
          "0x" + POINT_EWKB_BE_SRID,
          "0X" + POINT_EWKB_BE_SRID,
          "\\x" + POINT_EWKB_BE_SRID,
          "\\X" + POINT_EWKB_BE_SRID
        }) {
      Geometry geometry = (Geometry) meta.convertData(source, text);
      assertThat(geometry.getSRID()).as(text).isEqualTo(2056);
      assertThat(geometry.getGeometryType()).isEqualTo("Point");
    }

    assertThatThrownBy(() -> meta.convertData(source, "0x0"))
        .isInstanceOf(HopValueException.class);
    assertThatThrownBy(() -> codec.readText(POINT_EWKB_BE_SRID))
        .isInstanceOf(com.atolcd.hop.gis.geometry.codec.GeometryCodecException.class);
  }

  @Test
  void stringStorageVariantsAreNormalizedBeforeGeometryParsing() throws Exception {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    ExposedValueMetaString binaryString = new ExposedValueMetaString("text");
    binaryString.setStorageType(IValueMeta.STORAGE_TYPE_BINARY_STRING);
    binaryString.setStorageMetadata(new ValueMetaString("storage"));
    byte[] stored = binaryString.encodeForTest("SRID=2056;POINT (1 2)");
    Geometry fromBinaryString = (Geometry) meta.convertData(binaryString, stored);
    assertThat(fromBinaryString.getSRID()).isEqualTo(2056);

    ValueMetaString indexed = new ValueMetaString("text");
    indexed.setStorageType(IValueMeta.STORAGE_TYPE_INDEXED);
    indexed.setIndex(new Object[] {"POINT (3 4)"});
    Geometry fromIndexed = (Geometry) meta.convertData(indexed, Integer.valueOf(0));
    assertThat(((Point) fromIndexed).getX()).isEqualTo(3);

    ValueMetaBinary indexedBinary = new ValueMetaBinary("payload");
    indexedBinary.setStorageType(IValueMeta.STORAGE_TYPE_INDEXED);
    indexedBinary.setIndex(new Object[] {HexFormat.of().parseHex(POINT_XY_LE)});
    Geometry fromIndexedBinary = (Geometry) meta.convertData(indexedBinary, Integer.valueOf(0));
    assertThat(fromIndexedBinary.getGeometryType()).isEqualTo("Point");
  }

  @Test
  void invalidGeometryClassConversionStillFails() {
    ValueMetaGeometry meta = new ValueMetaGeometry("geom");
    assertThatThrownBy(() -> meta.convertData(new org.apache.hop.core.row.value.ValueMetaInteger("i"), 42L))
        .isInstanceOf(HopValueException.class);
  }

  private void assertBinaryConverted(
      IValueMeta target,
      IValueMeta source,
      String hex,
      CoordinateLayout layout,
      int srid)
      throws Exception {
    Geometry geometry =
        (Geometry) target.convertData(source, HexFormat.of().parseHex(hex));
    assertThat(GeometryLayoutSupport.aggregateLayout(geometry)).isEqualTo(layout);
    assertThat(geometry.getSRID()).isEqualTo(srid);
    assertThat(target.getType()).isEqualTo(ValueMetaGeometry.TYPE_GEOMETRY);
  }

  private static final class ExposedValueMetaString extends ValueMetaString {
    private ExposedValueMetaString(String name) {
      super(name);
    }

    private byte[] encodeForTest(String value) throws HopValueException {
      return convertStringToBinaryString(value);
    }
  }
}
