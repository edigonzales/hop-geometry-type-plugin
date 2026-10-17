package com.atolcd.hop.gis.geometry.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CompoundCurve;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import com.atolcd.hop.gis.geometry.curve.MultiCurve;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;

class GeometryTextCodecTest {
  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());

  @Test
  void nullAndBlankTextAreNull() throws Exception {
    assertThat(codec.readText(null)).isNull();
    assertThat(codec.readText("")).isNull();
    assertThat(codec.readText("   \t\n")).isNull();
  }

  @Test
  void parsesAllDeclaredLayouts() throws Exception {
    Point xy = (Point) codec.readText("POINT (1 2)");
    assertLayout(xy, CoordinateLayout.XY, 1, 2, Double.NaN, Double.NaN);

    Point xyz = (Point) codec.readText("POINT Z (1 2 NaN)");
    assertLayout(xyz, CoordinateLayout.XYZ, 1, 2, Double.NaN, Double.NaN);

    Point xym = (Point) codec.readText("POINT M (1 2 9)");
    assertLayout(xym, CoordinateLayout.XYM, 1, 2, Double.NaN, 9);

    Point xyzm = (Point) codec.readText("POINT ZM (1 2 NaN 9)");
    assertLayout(xyzm, CoordinateLayout.XYZM, 1, 2, Double.NaN, 9);
  }

  @Test
  void unmarkedThreeOrdinatesAreXyzAndFourAreRejected() throws Exception {
    Point xyz = (Point) codec.readText("POINT (1 2 3)");
    assertLayout(xyz, CoordinateLayout.XYZ, 1, 2, 3, Double.NaN);

    assertCode(
        () -> codec.readText("POINT (1 2 3 4)"), GeometryCodecErrorCode.GC_INVALID_WKT);
  }

  @Test
  void preservesEmptyLinearGeometriesAndTheirDeclaredLayout() throws Exception {
    Point point = (Point) codec.readText("POINT Z EMPTY");
    assertThat(point.isEmpty()).isTrue();
    assertThat(point.getCoordinateSequence().hasZ()).isTrue();

    LineString line = (LineString) codec.readText("LINESTRING M EMPTY");
    assertThat(line.isEmpty()).isTrue();
    assertThat(line.getCoordinateSequence().hasM()).isTrue();

    Geometry polygon = codec.readText("POLYGON EMPTY");
    assertThat(polygon.isEmpty()).isTrue();
    assertThat(GeometryLayoutSupport.aggregateLayout(polygon)).isEqualTo(CoordinateLayout.XY);
  }

  @Test
  void emptyCurveTypesAndDimensionedEmptyCollectionsAreRejected() {
    assertCode(
        () -> codec.readText("CIRCULARSTRING EMPTY"),
        GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE);
    assertCode(
        () -> codec.readText("COMPOUNDCURVE EMPTY"),
        GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE);
    assertCode(
        () -> codec.readText("CURVEPOLYGON EMPTY"),
        GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE);
    assertCode(
        () -> codec.readText("MULTIPOINT Z EMPTY"),
        GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT);
    assertCode(
        () -> codec.readText("GEOMETRYCOLLECTION M EMPTY"),
        GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT);
  }

  @Test
  void readsAndWritesEwktPrefix() throws Exception {
    Geometry geometry = codec.readText("srid=2056;POINT (1 2)");
    assertThat(geometry.getSRID()).isEqualTo(2056);
    assertThat(codec.writeText(geometry, WktWriteOptions.wkt())).isEqualTo("POINT (1 2)");
    assertThat(codec.writeText(geometry, WktWriteOptions.legacy()))
        .isEqualTo("SRID=2056;POINT (1 2)");
    assertThat(codec.writeText(geometry, WktWriteOptions.ewkt()))
        .isEqualTo("SRID=2056;POINT (1 2)");

    Geometry unknown = codec.readText("SRID=0;POINT (1 2)");
    assertThat(unknown.getSRID()).isZero();
    assertThat(codec.writeText(unknown, WktWriteOptions.ewkt())).isEqualTo("SRID=0;POINT (1 2)");
    assertThat(codec.writeText(unknown, WktWriteOptions.legacy())).isEqualTo("POINT (1 2)");
  }

  @Test
  void rejectsInvalidSridPrefixes() {
    for (String text :
        new String[] {
          "SRID=;POINT (1 2)",
          "SRID=-1;POINT (1 2)",
          "SRID=1.5;POINT (1 2)",
          "SRID=2147483648;POINT (1 2)",
          "SRID=2056",
          "SRID=1;SRID=2;POINT (1 2)",
          "FOO=2056;POINT (1 2)"
        }) {
      assertThatThrownBy(() -> codec.readText(text))
          .as(text)
          .isInstanceOf(GeometryCodecException.class);
    }
  }

  @Test
  void fullyConsumesInputAndRejectsTrailingGarbage() {
    assertCode(
        () -> codec.readText("POINT (1 2) garbage"), GeometryCodecErrorCode.GC_INVALID_WKT);
    assertCode(
        () -> codec.readText("POINT (1 2) POINT (3 4)"),
        GeometryCodecErrorCode.GC_INVALID_WKT);
    assertCode(
        () -> codec.readText("CIRCULARSTRING (1 0, 0 1, -1 0) garbage"),
        GeometryCodecErrorCode.GC_INVALID_WKT);
    assertCode(
        () -> codec.readText("COMPOUNDCURVE ((0 0, 1 1) BOGUS)"),
        GeometryCodecErrorCode.GC_INVALID_WKT);
    assertCode(
        () -> codec.readText("MULTISURFACE (BOGUS)"), GeometryCodecErrorCode.GC_INVALID_WKT);
  }

  @Test
  void parsesCurveNumbersWithExponentsSignsAndNanRows() throws Exception {
    CircularString curve =
        (CircularString) codec.readText("CIRCULARSTRING (+1e0 0, 0 1E+0, -1 0)");
    assertThat(curve.getControlPoints())
        .extracting(c -> c.x, c -> c.y)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1.0, 0.0),
            org.assertj.core.groups.Tuple.tuple(0.0, 1.0),
            org.assertj.core.groups.Tuple.tuple(-1.0, 0.0));

    CircularString withNan =
        (CircularString) codec.readText("CIRCULARSTRING Z (.5 0 NaN, 0 1. NaN, -1 0 7)");
    assertThat(withNan.getControlPointSequence().getDimension()).isEqualTo(3);
    assertThat(withNan.getControlPointSequence().getMeasures()).isZero();
    assertThat(withNan.getControlPoints()[0].getZ()).isNaN();

    for (String invalid :
        new String[] {
          "POINT (1e 2)",
          "POINT (1e+ 2)",
          "POINT (1d 2)",
          "POINT (0x1p3 2)",
          "POINT (1e999 2)",
          "POINT (1 2) // comment"
        }) {
      assertThatThrownBy(() -> codec.readText(invalid))
          .as(invalid)
          .isInstanceOf(GeometryCodecException.class);
    }
  }

  @Test
  void nanIsNotAllowedForXyAndInfinityIsNeverAllowed() {
    assertCode(
        () -> codec.readText("POINT (NaN 2)"),
        GeometryCodecErrorCode.GC_NONFINITE_COORDINATE);
    assertCode(
        () -> codec.readText("POINT Z (1 2 Infinity)"),
        GeometryCodecErrorCode.GC_NONFINITE_COORDINATE);
  }

  @Test
  void writesCanonicalLinearTextAndCollectionsWithoutLosingCurves() throws Exception {
    Geometry point = codec.readText("POINT Z (1 2 3)");
    assertThat(codec.writeText(point, WktWriteOptions.wkt())).isEqualTo("POINT Z (1 2 3)");

    Geometry collection =
        codec.readText(
            "GEOMETRYCOLLECTION (POINT (1 2), CIRCULARSTRING (1 0, 0 1, -1 0))");
    assertThat(collection).isInstanceOf(org.locationtech.jts.geom.GeometryCollection.class);
    String text = codec.writeText(collection, WktWriteOptions.wkt());
    assertThat(text)
        .isEqualTo(
            "GEOMETRYCOLLECTION (POINT (1 2), CIRCULARSTRING (1 0, 0 1, -1 0))");
  }

  @Test
  void readsAndWritesAllCurveTypes() throws Exception {
    for (String text :
        new String[] {
          "CIRCULARSTRING (1 0, 0 1, -1 0)",
          "COMPOUNDCURVE ((0 0, 1 0), CIRCULARSTRING (1 0, 2 1, 3 0))",
          "CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0))",
          "MULTICURVE ((0 0, 5 5), CIRCULARSTRING (10 10, 12 12, 14 10))",
          "MULTISURFACE (((0 0, 0 2, 2 2, 0 0)), CURVEPOLYGON (CIRCULARSTRING (10 0, 12 2, 14 0, 12 -2, 10 0)))"
        }) {
      Geometry geometry = codec.readText(text);
      Geometry roundTrip = codec.readText(codec.writeText(geometry, WktWriteOptions.wkt()));
      GeometryAssertions.assertGeometryPreserved(geometry, roundTrip);
    }
  }

  @Test
  void parserStateIsResetAfterErrorOnTheSameCodec() throws Exception {
    assertThatThrownBy(() -> codec.readText("CIRCULARSTRING (1 0, 0 1, -1 0) garbage"))
        .isInstanceOf(GeometryCodecException.class);
    Geometry geometry = codec.readText("CIRCULARSTRING (1 0, 0 1, -1 0)");
    assertThat(geometry).isInstanceOf(CircularString.class);
  }

  @Test
  void rejectsKnownUnsupportedTypesAndUnknownTypes() {
    assertCode(
        () -> codec.readText("TIN (((0 0, 1 0, 0 1, 0 0)))"),
        GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY);
    assertCode(() -> codec.readText("BOGUS (1 2)"), GeometryCodecErrorCode.GC_INVALID_WKT);
  }

  @Test
  void splitSridPrefixRemainsPackageTestable() throws Exception {
    GeometryTextCodec textCodec = new GeometryTextCodec(GeometryReadLimits.defaults());
    GeometryTextCodec.EwktParts parts =
        textCodec.splitSridPrefix("  SRID=2056;POINT (1 2)  ");
    assertThat(parts.explicitSrid()).isEqualTo(2056);
    assertThat(parts.body()).isEqualTo("POINT (1 2)");
    assertThat(textCodec.splitSridPrefix("POINT (1 2)").explicitSrid()).isNull();
  }

  @Test
  void curvePolygonAndCompoundCurveKeepExactControls() throws Exception {
    CurvePolygon polygon =
        (CurvePolygon)
            codec.readText("CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0))");
    assertThat(polygon.getCurveRings().get(0)).isInstanceOf(CircularString.class);
    assertThat(((CircularString) polygon.getCurveRings().get(0)).getControlPoints()).hasSize(5);

    CompoundCurve curve =
        (CompoundCurve)
            codec.readText(
                "COMPOUNDCURVE ((0 0, 1 0), CIRCULARSTRING (1 0, 2 1, 3 0))");
    assertThat(curve.getComponents()).hasSize(2);
    assertThat(curve.getComponents().get(0)).isInstanceOf(LineString.class);
    assertThat(curve.getComponents().get(1)).isInstanceOf(CircularString.class);
    assertThat(((CircularString) curve.getComponents().get(1)).getControlPoints())
        .extracting(c -> c.x, c -> c.y)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1.0, 0.0),
            org.assertj.core.groups.Tuple.tuple(2.0, 1.0),
            org.assertj.core.groups.Tuple.tuple(3.0, 0.0));
  }

  @Test
  void collectsAreNotSilentlyAcceptedAsMultiCurves() throws Exception {
    MultiCurve multiCurve =
        (MultiCurve)
            codec.readText(
                "MULTICURVE (LINESTRING Z (10 0 NaN, 12 2 NaN), CIRCULARSTRING (0 0, 2 2, 4 0))");
    assertThat(multiCurve.getCurves()).hasSize(2);
    assertThat(GeometryLayoutSupport.aggregateLayout(multiCurve))
        .isEqualTo(CoordinateLayout.XYZ);
  }

  private static void assertLayout(
      Point point,
      CoordinateLayout layout,
      double x,
      double y,
      double z,
      double m) {
    var sequence = point.getCoordinateSequence();
    assertThat(sequence.getDimension()).isEqualTo(layout.dimension());
    assertThat(sequence.getMeasures()).isEqualTo(layout.measures());
    assertThat(sequence.getX(0)).isEqualTo(x);
    assertThat(sequence.getY(0)).isEqualTo(y);
    if (layout.hasZ()) {
      GeometryAssertions.assertOrdinate(z, sequence.getZ(0), "z");
    }
    if (layout.hasM()) {
      GeometryAssertions.assertOrdinate(m, sequence.getM(0), "m");
    }
  }

  private static void assertCode(ThrowingCallable call, GeometryCodecErrorCode code) {
    assertThatThrownBy(call)
        .isInstanceOf(GeometryCodecException.class)
        .satisfies(
            error ->
                assertThat(((GeometryCodecException) error).code())
                    .as(error.getMessage())
                    .isEqualTo(code));
  }
}
