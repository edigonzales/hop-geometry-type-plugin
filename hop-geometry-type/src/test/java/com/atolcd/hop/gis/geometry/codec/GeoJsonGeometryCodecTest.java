package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

class GeoJsonGeometryCodecTest {
  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());

  @Test void referenceAxesAreNotAReprojection() throws Exception {
    GeoJsonReadResult rfc = codec.readGeoJson("{\"type\":\"Point\",\"coordinates\":[7.4386,46.9511]}", null);
    Point p = (Point)rfc.geometry();
    assertThat(p.getX()).isEqualTo(7.4386); assertThat(p.getY()).isEqualTo(46.9511);
    assertThat(p.getSRID()).isEqualTo(4326);
    GeoJsonReadResult fg = codec.readGeoJson(fg("{\"type\":\"Point\",\"coordRefSys\":\"http://www.opengis.net/def/crs/EPSG/0/4326\",\"coordinates\":[46.9511,7.4386]}"), null);
    assertThat(fg.geometry().equalsExact(p)).isTrue();
    Map<String,Object> output = codec.writeGeoJsonObject(p, fgOptions(fg.context()));
    assertThat(output.get("coordinates")).isEqualTo(List.of(46.9511,7.4386));
    GeoJsonReadResult lv95 = codec.readGeoJson(fg("{\"type\":\"Point\",\"coordRefSys\":\"EPSG:2056\",\"coordinates\":[2600000,1200000]}"), null);
    assertThat(((Point)lv95.geometry()).getX()).isEqualTo(2600000);
    GeoJsonReadResult laea = codec.readGeoJson(fg("{\"type\":\"Point\",\"coordRefSys\":\"EPSG:3035\",\"coordinates\":[3210000,4321000]}"), null);
    assertThat(((Point)laea.geometry()).getX()).isEqualTo(4321000);
    assertThat(((Point)laea.geometry()).getY()).isEqualTo(3210000);
  }

  @ParameterizedTest @ValueSource(strings={
    "POINT (7 47)", "LINESTRING (7 47,8 48)",
    "POLYGON ((7 47,8 47,8 48,7 48,7 47),(7.2 47.2,7.3 47.2,7.2 47.3,7.2 47.2))",
    "MULTIPOINT ((7 47),(8 48))", "MULTILINESTRING ((7 47,8 48),(8 48,9 49))",
    "MULTIPOLYGON (((7 47,8 47,8 48,7 47)))",
    "GEOMETRYCOLLECTION (POINT (7 47),GEOMETRYCOLLECTION (LINESTRING (7 47,8 48)))"})
  void standardShapesRoundTripAndDoNotMutate(String wkt) throws Exception {
    Geometry source = codec.readText("SRID=4326;"+wkt);
    byte[] before = codec.writeBinary(source,WkbWriteOptions.standardFieldBinary());
    String json = codec.writeGeoJson(source,null);
    Geometry back = codec.readGeoJson(json,null).geometry();
    assertThat(back.equalsTopo(source)).isTrue();
    assertThat(codec.writeBinary(source,WkbWriteOptions.standardFieldBinary())).isEqualTo(before);
    assertThat(back.getSRID()).isEqualTo(4326);
  }

  @ParameterizedTest @ValueSource(strings={
    "POINT M (7 47 19)", "POINT ZM (7 47 500 19)",
    "CIRCULARSTRING (0 0,1 1,2 0)",
    "COMPOUNDCURVE ((0 0,1 0),CIRCULARSTRING (1 0,2 1,3 0))",
    "CURVEPOLYGON (CIRCULARSTRING (0 0,1 1,2 0,1 -1,0 0))",
    "MULTICURVE (CIRCULARSTRING (0 0,1 1,2 0),(2 0,3 0))",
    "MULTISURFACE (CURVEPOLYGON (CIRCULARSTRING (0 0,1 1,2 0,1 -1,0 0)))"})
  void jsonFgPreservesExactCurvesAndMeasures(String wkt) throws Exception {
    int srid = wkt.contains(" ZM ") ? 4979 : 4326;
    Geometry source = codec.readText("SRID="+srid+";"+wkt);
    String json = codec.writeGeoJson(source,fgOptions(GeoJsonContext.empty()));
    Geometry back = codec.readGeoJson(json,null).geometry();
    assertThat(back.getClass()).isEqualTo(source.getClass());
    assertThat(codec.writeBinary(back,WkbWriteOptions.standardFieldBinary())).isEqualTo(codec.writeBinary(source,WkbWriteOptions.standardFieldBinary()));
  }

  @Test void compoundVerticalAndMeasuresContextRoundTrip() throws Exception {
    GeoJsonContext context = new GeoJsonContext(GeoJsonProfile.JSONFG10,
        List.of("EPSG:2056","EPSG:5728"), Map.of("enabled",true,"unit","s","description","elapsed"));
    Geometry source = codec.readText("SRID=2056;POINT ZM (2600000 1200000 470 12.5)");
    GeoJsonReadResult result = codec.readGeoJson(codec.writeGeoJson(source,fgOptions(context)),null);
    assertThat(result.context()).isEqualTo(context);
    assertThat(result.context().toJson()).contains("5728","elapsed");
    assertThat(GeoJsonContext.fromJson(context.toJson())).isEqualTo(context);
    assertThat(codec.writeText(result.geometry(),WktWriteOptions.ewkt())).contains("2600000 1200000 470 12.5");
  }

  @Test void threeDimensionalRfcUsesEllipsoidalHeightAndExplicitLoss() throws Exception {
    GeoJsonReadResult xyz = codec.readGeoJson("{\"type\":\"Point\",\"coordinates\":[7,47,500]}",null);
    assertThat(xyz.geometry().getSRID()).isEqualTo(4979);
    assertThat(xyz.context().coordRefSys()).isEqualTo(GeoJsonCrs.CRS84H);
    assertThat(codec.writeGeoJson(xyz.geometry(),null)).contains("500.0");
    Geometry ungrounded = codec.readText("SRID=4326;POINT Z (7 47 500)");
    assertThatThrownBy(() -> codec.writeGeoJson(ungrounded,null)).hasMessageContaining("GC_UNSUPPORTED_CRS");
    GeoJsonWriteOptions explicit = new GeoJsonWriteOptions(GeoJsonProfile.RFC7946,
        new GeoJsonContext(GeoJsonProfile.RFC7946,GeoJsonCrs.CRS84H,Map.of()),false,false,false);
    assertThat(codec.writeGeoJson(ungrounded,explicit)).contains("500.0");
    assertThat(codec.writeGeoJson(ungrounded,new GeoJsonWriteOptions(GeoJsonProfile.RFC7946,null,true,false,false))).doesNotContain("500");
  }

  @Test void legacyNamedCrsUsesXYAndInheritedRootContext() throws Exception {
    String json = "{\"type\":\"Point\",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"urn:ogc:def:crs:EPSG::2056\"}},\"coordinates\":[2600000,1200000]}";
    GeoJsonReadResult result = codec.readGeoJson(json,null);
    assertThat(result.context().profile()).isEqualTo(GeoJsonProfile.LEGACY2008);
    assertThat(((Point)result.geometry()).getX()).isEqualTo(2600000);
    assertThat(codec.writeGeoJson(result.geometry(),new GeoJsonWriteOptions(GeoJsonProfile.LEGACY2008,result.context(),false,false,false))).contains("urn:ogc:def:crs:EPSG::2056");
    GeoJsonReadResult inherited = codec.readGeoJson("{\"type\":\"Point\",\"coordinates\":[2600000,1200000]}",new GeoJsonReadOptions(GeoJsonProfile.AUTO,result.context()));
    assertThat(inherited.geometry().getSRID()).isEqualTo(2056);
  }

  @Test void nullEmptyAndEmbeddedEmptyAreExplicit() throws Exception {
    assertThat(codec.readGeoJson("null",null).geometry()).isNull();
    assertThat(codec.writeGeoJson(null,null)).isEqualTo("null");
    Geometry empty = codec.readText("SRID=4326;POINT EMPTY");
    assertThatThrownBy(() -> codec.writeGeoJson(empty,null)).hasMessageContaining("GC_LOSSY_CONVERSION");
    assertThat(codec.writeGeoJson(empty,new GeoJsonWriteOptions(GeoJsonProfile.RFC7946,null,false,false,true))).isEqualTo("null");
    Geometry collection = codec.readText("SRID=4326;GEOMETRYCOLLECTION EMPTY");
    assertThat(codec.writeGeoJson(collection,null)).contains("\"geometries\":[]");
    assertThat(codec.readGeoJson("{\"type\":\"Point\",\"coordinates\":[]}",null).geometry().isEmpty()).isTrue();
  }

  @Test void strictLossPoliciesRejectMeasuresCurvesAndNonfiniteValues() throws Exception {
    Geometry m = codec.readText("SRID=4326;POINT M (7 47 20)");
    assertThatThrownBy(() -> codec.writeGeoJson(m,null)).hasMessageContaining("GC_LOSSY_CONVERSION");
    assertThat(codec.writeGeoJson(m,new GeoJsonWriteOptions(GeoJsonProfile.RFC7946,null,false,true,false))).contains("[7.0,47.0]");
    Geometry curve = codec.readText("SRID=4326;CIRCULARSTRING (0 0,1 1,2 0)");
    assertThatThrownBy(() -> codec.writeGeoJson(curve,null)).hasMessageContaining("True curves");
    Point legacyNan = new org.locationtech.jts.geom.GeometryFactory().createPoint(new org.locationtech.jts.geom.Coordinate(7,47,Double.NaN));
    legacyNan.setSRID(4979);
    assertThatThrownBy(() -> codec.writeGeoJson(legacyNan,null)).hasMessageContaining("GC_NONFINITE_COORDINATE");
    Geometry nan = codec.readText("SRID=4979;POINT Z (7 47 NaN)");
    assertThatThrownBy(() -> codec.writeGeoJson(nan,null)).hasMessageContaining("GC_NONFINITE_COORDINATE");
    assertThatThrownBy(() -> codec.readGeoJson("{\"type\":\"Point\",\"coordinates\":[1e999,2]}",null)).hasMessageContaining("GC_NONFINITE_COORDINATE");
  }

  @ParameterizedTest @ValueSource(strings={
    "{\"type\":\"Feature\",\"geometry\":null,\"properties\":{}}",
    "{\"type\":\"FeatureCollection\",\"features\":[]}",
    "{\"type\":\"Point\",\"coordinates\":[1,2,3,4]}",
    "{\"type\":\"LineString\",\"coordinates\":[[1,2],[1,2,3]]}",
    "{\"type\":\"Point\",\"coordRefSys\":\"EPSG:4326\",\"coordinates\":[1,2,3]}",
    "{\"type\":\"Point\",\"coordRefSys\":\"EPSG:4978\",\"coordinates\":[1,2,3]}",
    "{\"type\":\"Point\",\"coordRefSys\":\"EPSG:999999999\",\"coordinates\":[1,2]}",
    "{\"type\":\"Point\",\"crs\":null,\"coordinates\":[1,2]}",
    "{\"type\":\"Point\",\"coordRefSys\":{\"type\":\"Reference\",\"href\":\"EPSG:4326\",\"epoch\":2020},\"coordinates\":[1,2]}",
    "{\"type\":\"Point\",\"conformsTo\":[\"http://www.opengis.net/spec/json-fg-1/9.0/conf/core\"],\"coordinates\":[1,2]}"})
  void invalidInputsAreRejected(String json) {
    String input = json.contains("coordRefSys") && !json.contains("conformsTo") ? fg(json) : json;
    assertThatThrownBy(() -> codec.readGeoJson(input,null)).isInstanceOf(GeometryCodecException.class);
  }

  @Test void conformanceAndEmbeddedMetadataAreValidated() throws Exception {
    assertThatThrownBy(() -> codec.readGeoJson("{\"type\":\"Point\",\"coordRefSys\":\"EPSG:4326\",\"coordinates\":[47,7]}",null)).hasMessageContaining("conformsTo");
    String noCore = "{\"type\":\"Point\",\"coordRefSys\":\"EPSG:4326\",\"conformsTo\":[],\"coordinates\":[47,7]}";
    assertThatThrownBy(() -> codec.readGeoJson(noCore,null)).hasMessageContaining("core");
    String missingMeasures = "{\"type\":\"Point\",\"coordRefSys\":\"EPSG:4326\",\"measures\":{\"enabled\":true},\"conformsTo\":[\""+GeoJsonContext.JSONFG_CORE+"\"],\"coordinates\":[47,7,2]}";
    assertThatThrownBy(() -> codec.readGeoJson(missingMeasures,null)).hasMessageContaining("measures conformance");
    Geometry nested = codec.readText("SRID=4326;GEOMETRYCOLLECTION (GEOMETRYCOLLECTION (POINT (7 47)))");
    assertThatThrownBy(() -> codec.writeGeoJson(nested,fgOptions(null))).hasMessageContaining("non-nested");
  }

  @Test void rfcWindingIsNormalizedOnACopy() throws Exception {
    Polygon source = (Polygon)codec.readText("SRID=4326;POLYGON ((0 0,0 2,2 2,0 0))");
    assertThat(Orientation.isCCW(source.getExteriorRing().getCoordinateSequence())).isFalse();
    Polygon result = (Polygon)codec.readGeoJson(codec.writeGeoJson(source,null),null).geometry();
    assertThat(Orientation.isCCW(result.getExteriorRing().getCoordinateSequence())).isTrue();
    assertThat(Orientation.isCCW(source.getExteriorRing().getCoordinateSequence())).isFalse();
  }

  @Test void geometryBudgetsApplyBeforeAllocationAndCurveMaterialization() {
    GeometryCodec small = new GeometryCodec(new GeometryReadLimits(2,2,3,2));
    assertThatThrownBy(() -> small.readGeoJson("{\"type\":\"LineString\",\"coordinates\":[[0,0],[1,1],[2,2]]}",null)).hasMessageContaining("GC_LIMIT_EXCEEDED");
    assertThatThrownBy(() -> small.readGeoJson("{\"type\":\"GeometryCollection\",\"geometries\":[{\"type\":\"GeometryCollection\",\"geometries\":[{\"type\":\"Point\",\"coordinates\":[1,2]}]}]}",null)).hasMessageContaining("GC_LIMIT_EXCEEDED");
    GeometryCodec tinyCurve = new GeometryCodec(new GeometryReadLimits(64,100,100,2));
    assertThatThrownBy(() -> tinyCurve.readGeoJson(fg("{\"type\":\"CircularString\",\"coordRefSys\":\"EPSG:2056\",\"coordinates\":[[0,0],[1,1],[2,0]]}"),null)).hasMessageContaining("GC_LIMIT_EXCEEDED");
  }

  @Test void contextClassloaderIsRestoredOnSuccessAndFailure() throws Exception {
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    ClassLoader isolated = new ClassLoader(null) {};
    try {
      Thread.currentThread().setContextClassLoader(isolated);
      codec.readGeoJson(fg("{\"type\":\"Point\",\"coordRefSys\":\"EPSG:32632\",\"coordinates\":[400000,5000000]}"),null);
      assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(isolated);
      assertThatThrownBy(() -> codec.readGeoJson(fg("{\"type\":\"Point\",\"coordRefSys\":\"EPSG:987654321\",\"coordinates\":[1,2]}"),null)).isInstanceOf(GeometryCodecException.class);
      assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(isolated);
    } finally { Thread.currentThread().setContextClassLoader(original); }
  }

  @Test void circularArcBoundariesAndMeasureConsistencyAreEnforced() throws Exception {
    String eleven = "SRID=2056;CIRCULARSTRING (0 0,1 1,2 0,3 -1,4 0,5 1,6 0,7 -1,8 0,9 1,10 0)";
    Geometry good = codec.readText(eleven);
    assertThat(codec.readGeoJson(codec.writeGeoJson(good,fgOptions(null)),null).geometry()).isInstanceOf(CircularString.class);
    Geometry thirteen = codec.readText(eleven.replace("10 0)","10 0,11 -1,12 0)"));
    assertThatThrownBy(() -> codec.writeGeoJson(thirteen,fgOptions(null))).hasMessageContaining("3, 5, 7, 9 or 11");
    Geometry measured = codec.readText("SRID=4326;POINT M (7 47 8)");
    assertThatThrownBy(() -> codec.writeGeoJson(measured,fgOptions(new GeoJsonContext(GeoJsonProfile.JSONFG10,"EPSG:4326",Map.of("enabled",false))))).hasMessageContaining("enabled=true");
  }

  @Test void emittedJsonFgSamplesFeedIndependentOfflineSchemaValidation() throws Exception {
    List<Object> samples = new ArrayList<>();
    for (String wkt : List.of("POINT (1 2)","POINT M (1 2 3)","POINT ZM (1 2 3 4)",
        "POINT Z (7 47 500)","LINESTRING (0 0,1 1)",
        "POLYGON ((0 0,4 0,4 4,0 4,0 0),(1 1,2 1,2 2,1 2,1 1))",
        "MULTIPOINT ((1 2),(3 4))","MULTILINESTRING ((0 0,1 1),(1 1,2 0))",
        "MULTIPOLYGON (((0 0,1 0,1 1,0 0)))","GEOMETRYCOLLECTION EMPTY",
        "CIRCULARSTRING (0 0,1 1,2 0)","COMPOUNDCURVE ((0 0,1 0),CIRCULARSTRING (1 0,2 1,3 0))",
        "CURVEPOLYGON (CIRCULARSTRING (0 0,1 1,2 0,1 -1,0 0))",
        "MULTICURVE (CIRCULARSTRING (0 0,1 1,2 0))",
        "MULTISURFACE (CURVEPOLYGON (CIRCULARSTRING (0 0,1 1,2 0,1 -1,0 0)))",
        "GEOMETRYCOLLECTION (POINT (1 2),LINESTRING (1 2,3 4))")) {
      Geometry geometry = codec.readText("SRID="+((wkt.contains(" ZM ") || wkt.contains(" Z "))?4979:4326)+";"+wkt);
      samples.add(codec.writeGeoJsonObject(geometry,fgOptions(null)));
    }
    samples.add(codec.writeGeoJsonObject(codec.readText("SRID=2056;POINT ZM (2600000 1200000 500 19)"),
        fgOptions(new GeoJsonContext(GeoJsonProfile.JSONFG10,List.of("EPSG:2056","EPSG:5728"),Map.of("enabled",true,"unit","s")))));
    samples.add(codec.writeGeoJsonObject(codec.readText("SRID=4326;POINT (7 47)"),
        fgOptions(new GeoJsonContext(GeoJsonProfile.JSONFG10,"EPSG:4326",Map.of("enabled",false,"description","No measures")))));
    Path output = Path.of("target/jsonfg-emitted.json");
    Files.createDirectories(output.getParent()); Files.writeString(output,JsonSupport.stringify(samples));
    assertThat(samples).hasSize(18);
  }

  private static String fg(String value) {
    return value.substring(0,value.length()-1) + ",\"conformsTo\":[\"" + GeoJsonContext.JSONFG_CORE + "\"]}";
  }

  private GeoJsonWriteOptions fgOptions(GeoJsonContext context) {
    return new GeoJsonWriteOptions(GeoJsonProfile.JSONFG10,context,false,false,false);
  }
}
