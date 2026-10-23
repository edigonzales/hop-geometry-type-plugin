package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;

class GeoJsonAdversarialTest {
  private final GeometryCodec codec=new GeometryCodec(GeometryReadLimits.defaults());
  private final GeoJsonWriteOptions fg=new GeoJsonWriteOptions(GeoJsonProfile.JSONFG10,null,false,false,false);

  @Test void mixedMeasureCollectionsAndMultiGeometriesAreRejectedBeforeEmission() throws Exception {
    for(String wkt:List.of("GEOMETRYCOLLECTION (POINT (7 47),POINT M (8 48 9))",
        "MULTIPOINT ((7 47))")) {
      Geometry geometry=codec.readText("SRID=4326;"+wkt);
      if(wkt.startsWith("MULTIPOINT")) geometry=geometry.getFactory().createMultiPoint(new Point[]{
          (Point)codec.readText("SRID=4326;POINT (7 47)"),(Point)codec.readText("SRID=4326;POINT M (8 48 9)")});
      Geometry value=GeometrySridSupport.copyWithSrid(geometry,4326);
      assertThatThrownBy(()->codec.writeGeoJson(value,fg)).hasMessageContaining("measures dimensions");
      String json=codec.writeGeoJson(value,new GeoJsonWriteOptions(GeoJsonProfile.JSONFG10,null,false,true,false));
      assertThat(codec.readGeoJson(json,null).geometry()).isNotNull();
    }
  }
  @Test void deeplyNestedNativeInputHasACheckedDepthFailure() throws Exception {
    GeometryFactory factory=new GeometryFactory();
    Geometry geometry=codec.readText("SRID=4326;POINT (7 47)");
    for(int i=0;i<10000;i++) geometry=factory.createGeometryCollection(new Geometry[]{geometry});
    Geometry deep=geometry;
    assertThatThrownBy(()->codec.writeGeoJson(deep,null)).isInstanceOf(GeometryCodecException.class).hasMessageContaining("GC_LIMIT_EXCEEDED");
  }
  @Test void declaredNanDimensionsRequireExplicitDropForJtsLinesAndPolygons() throws Exception {
    GeometryFactory factory=new GeometryFactory();
    Geometry line=factory.createLineString(new Coordinate[]{new Coordinate(7,47),new Coordinate(8,48)});
    line.setSRID(4979);
    assertThatThrownBy(()->codec.writeGeoJson(line,null)).hasMessageContaining("GC_NONFINITE_COORDINATE");
    assertThat(codec.writeGeoJson(line,new GeoJsonWriteOptions(GeoJsonProfile.RFC7946,null,true,false,false))).contains("[7.0,47.0]");
    Geometry polygon=factory.createPolygon(new Coordinate[]{new Coordinate(0,0),new Coordinate(1,0),new Coordinate(1,1),new Coordinate(0,0)});
    polygon.setSRID(4979);
    assertThatThrownBy(()->codec.writeGeoJson(polygon,null)).isInstanceOf(GeometryCodecException.class);
  }
  @Test void conflictingAndNullCrsMetadataNeverDefaultsToWgs84() {
    String core="\"conformsTo\":[\""+GeoJsonContext.JSONFG_CORE+"\"],";
    for(String member:List.of("\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"EPSG:2056\"}},",
        "\"coordRefSys\":null,")) {
      String input="{\"type\":\"Point\","+core+member+"\"coordinates\":[2600000,1200000]}";
      assertThatThrownBy(()->codec.readGeoJson(input,null)).isInstanceOf(GeometryCodecException.class);
    }
    String nested="{\"type\":\"GeometryCollection\",\"geometries\":[{\"type\":\"Point\",\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"EPSG:2056\"}},\"coordinates\":[1,2]}]}";
    assertThatThrownBy(()->codec.readGeoJson(nested,null)).hasMessageContaining("CRS");
  }
  @Test void fullPositionCurveJoinsAndRingClosureAreChecked() throws Exception {
    Geometry disconnected=codec.readText("SRID=4979;COMPOUNDCURVE Z ((0 0 0,1 1 0),(1 1 99,2 2 99))");
    assertThatThrownBy(()->codec.writeGeoJson(disconnected,fg)).hasMessageContaining("consecutive");
    GeoJsonWriteOptions drop=new GeoJsonWriteOptions(GeoJsonProfile.JSONFG10,null,true,false,false);
    assertThat(codec.writeGeoJson(disconnected,drop)).doesNotContain("99");
    String json="{\"type\":\"CompoundCurve\",\"coordRefSys\":\"EPSG:4979\",\"conformsTo\":[\""+GeoJsonContext.JSONFG_CORE+"\",\""+GeoJsonContext.JSONFG_CURVES+"\"],\"geometries\":[{\"type\":\"LineString\",\"coordinates\":[[0,0,0],[1,1,0]]},{\"type\":\"LineString\",\"coordinates\":[[1,1,99],[2,2,99]]}]}";
    assertThatThrownBy(()->codec.readGeoJson(json,null)).hasMessageContaining("join consecutively");
  }
  @ParameterizedTest @ValueSource(ints={3031,3413,2046})
  void authorityAxesHandlePolarAndSouthOrientedProjectedCrs(int epsg) throws Exception {
    String json="{\"type\":\"Point\",\"coordRefSys\":\"EPSG:"+epsg+"\",\"conformsTo\":[\""+GeoJsonContext.JSONFG_CORE+"\"],\"coordinates\":[123,456]}";
    GeoJsonReadResult result=codec.readGeoJson(json,null);
    assertThat(((Point)result.geometry()).getX()).isEqualTo(123);
    assertThat(((Point)result.geometry()).getY()).isEqualTo(456);
    assertThat(codec.readGeoJson(codec.writeGeoJson(result.geometry(),new GeoJsonWriteOptions(GeoJsonProfile.JSONFG10,result.context(),false,false,false)),null).geometry().equalsExact(result.geometry())).isTrue();
  }
  @Test void defaultJsonFgCrsAccountsForHeightAndMeasures() throws Exception {
    String core="\"conformsTo\":[\""+GeoJsonContext.JSONFG_CORE+"\",\""+GeoJsonContext.JSONFG_MEASURES+"\"]";
    for(String input:List.of("{\"type\":\"Point\",\"coordinates\":[7,47,500],"+core+"}",
        "{\"type\":\"Point\",\"coordinates\":[7,47,500,19],\"measures\":{\"enabled\":true},"+core+"}")) {
      GeoJsonReadResult result=codec.readGeoJson(input,null);
      Point point=(Point)result.geometry();
      assertThat(point.getSRID()).isEqualTo(4979);
      assertThat(point.getX()).isEqualTo(7);assertThat(point.getY()).isEqualTo(47);
      assertThat(point.getCoordinateSequence().getZ(0)).isEqualTo(500);
      assertThat(result.context().coordRefSys()).isEqualTo(GeoJsonCrs.CRS84H);
    }
  }
  @Test void legacyXyzWithHorizontalCrsPreservesHeightWithoutInventingADatum() throws Exception {
    String input="{\"type\":\"Point\",\"coordinates\":[2600000,1200000,500],\"crs\":{\"type\":\"name\",\"properties\":{\"name\":\"EPSG:2056\"}}}";
    GeoJsonReadResult result=codec.readGeoJson(input,null);
    String output=codec.writeGeoJson(result.geometry(),new GeoJsonWriteOptions(GeoJsonProfile.LEGACY2008,result.context(),false,false,false));
    assertThat(output).contains("500.0");
    assertThat(codec.readGeoJson(output,null).geometry().equalsExact(result.geometry())).isTrue();
  }
  @Test void disabledMeasureMetadataAndEmptyThreeDimensionalContextArePreserved() throws Exception {
    Geometry point=codec.readText("SRID=4326;POINT (7 47)");
    GeoJsonContext disabled=new GeoJsonContext(GeoJsonProfile.JSONFG10,"EPSG:4326",Map.of("enabled",false,"description","No measurements available"));
    String output=codec.writeGeoJson(point,new GeoJsonWriteOptions(GeoJsonProfile.JSONFG10,disabled,false,false,false));
    assertThat(codec.readGeoJson(output,null).context().measures()).isEqualTo(disabled.measures());
    assertThatThrownBy(()->codec.writeGeoJson(point,new GeoJsonWriteOptions(GeoJsonProfile.RFC7946,disabled,false,false,false))).hasMessageContaining("Measure metadata");
    Geometry empty=codec.readText("SRID=4979;GEOMETRYCOLLECTION EMPTY");
    String fgEmpty=codec.writeGeoJson(empty,fg);
    assertThat(codec.readGeoJson(fgEmpty,null).geometry().getSRID()).isEqualTo(4979);
    assertThatThrownBy(()->codec.writeGeoJson(empty,null)).hasMessageContaining("EMPTY 3D collection");
  }
  @Test void actualSimpleFeatureChildrenOfCurvesMustBeValid() throws Exception {
    for(String wkt:List.of("COMPOUNDCURVE ((7 47,7 47))","MULTICURVE ((7 47,7 47))")) {
      Geometry geometry=codec.readText("SRID=4326;"+wkt);
      assertThatThrownBy(()->codec.writeGeoJson(geometry,fg)).hasMessageContaining("Invalid JSON-FG Simple Feature");
    }
  }
  @Test void unknownGeometryMembersConformanceTypesAndInvalidTopologyFail() throws Exception {
    assertThatThrownBy(()->codec.readGeoJson("{\"type\":\"Point\",\"coordinates\":[7,47],\"survey\":{\"unit\":\"m\"}}",null)).hasMessageContaining("would be lost");
    String bad="{\"type\":\"Point\",\"coordinates\":[7,47],\"conformsTo\":[\""+GeoJsonContext.JSONFG_CORE+"\",123]}";
    assertThatThrownBy(()->codec.readGeoJson(bad,null)).hasMessageContaining("strings");
    Geometry bowtie=codec.readText("SRID=4326;POLYGON ((0 0,2 2,0 2,2 0,0 0))");
    assertThatThrownBy(()->codec.writeGeoJson(bowtie,fg)).hasMessageContaining("Invalid JSON-FG Simple Feature");
  }
}
