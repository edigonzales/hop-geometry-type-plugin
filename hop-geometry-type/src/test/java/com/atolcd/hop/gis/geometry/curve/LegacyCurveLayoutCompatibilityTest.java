package com.atolcd.hop.gis.geometry.curve;

import static org.assertj.core.api.Assertions.assertThat;

import com.atolcd.hop.gis.geometry.codec.CoordinateLayout;
import com.atolcd.hop.gis.geometry.codec.GeometryCodec;
import com.atolcd.hop.gis.geometry.codec.GeometryLayoutSupport;
import com.atolcd.hop.gis.geometry.codec.GeometryReadLimits;
import com.atolcd.hop.gis.geometry.codec.WkbWriteOptions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/** Protects the existing Geoprocessing consumer contract for JTS-created curve components. */
class LegacyCurveLayoutCompatibilityTest {
  private final GeometryFactory factory = new GeometryFactory();
  private final GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());

  @Test
  void compoundCurveWithJtsLineAndCircularStringStillRoundTrips() throws Exception {
    CircularString circular =
        new CircularString(
            new Coordinate[] {
              new Coordinate(0, 0), new Coordinate(1, 1), new Coordinate(2, 0)
            },
            factory);
    LineString line =
        factory.createLineString(
            new Coordinate[] {new Coordinate(2, 0), new Coordinate(3, 0)});
    CompoundCurve source = new CompoundCurve(List.of(circular, line), factory);
    source.setSRID(2056);

    byte[] wkb = CurveGeometrySupport.writeWkb(source);
    Geometry decoded = CurveGeometrySupport.readWkb(wkb);

    assertThat(decoded).isInstanceOf(CompoundCurve.class);
    assertThat(decoded.getSRID()).isEqualTo(2056);
    assertThat(((CompoundCurve) decoded).getComponents()).hasSize(2);
    assertThat(((CompoundCurve) decoded).getComponents().get(1)).isInstanceOf(LineString.class);
  }

  @Test
  void multiCurveAndMultiSurfaceWithJtsLineOrPolygonStillRoundTrip() throws Exception {
    CircularString circular =
        new CircularString(
            new Coordinate[] {
              new Coordinate(0, 0), new Coordinate(1, 1), new Coordinate(2, 0)
            },
            factory);
    MultiCurve multiCurve =
        new MultiCurve(
            List.of(
                factory.createLineString(
                    new Coordinate[] {new Coordinate(-2, 0), new Coordinate(-1, 0)}),
                circular),
            factory);
    multiCurve.setSRID(2056);
    Geometry decodedCurve = CurveGeometrySupport.readWkb(CurveGeometrySupport.writeWkb(multiCurve));
    assertThat(decodedCurve).isInstanceOf(MultiCurve.class);
    assertThat(decodedCurve.getSRID()).isEqualTo(2056);

    CurvePolygon polygon =
        new CurvePolygon(
            List.of(
                new CircularString(
                    new Coordinate[] {
                      new Coordinate(0, 0),
                      new Coordinate(4, 0),
                      new Coordinate(4, 4),
                      new Coordinate(0, 4),
                      new Coordinate(0, 0)
                    },
                    factory)),
            factory);
    MultiSurface multiSurface =
        new MultiSurface(
            List.of(
                factory.createPolygon(
                    new Coordinate[] {
                      new Coordinate(-3, -3),
                      new Coordinate(-2, -3),
                      new Coordinate(-2, -2),
                      new Coordinate(-3, -3)
                    }),
                polygon),
            factory);
    Geometry decodedSurface =
        CurveGeometrySupport.readWkb(CurveGeometrySupport.writeWkb(multiSurface));
    assertThat(decodedSurface).isInstanceOf(MultiSurface.class);
  }

  @Test
  void explicitAllNanZLayoutsFromTheCodecAreStillPreserved() throws Exception {
    Geometry line = codec.readText("LINESTRING Z (0 0 NaN, 1 1 NaN)");
    assertThat(GeometryLayoutSupport.primitiveLayout(line)).isEqualTo(CoordinateLayout.XYZ);
    byte[] wkb = codec.writeBinary(line, WkbWriteOptions.standardFieldBinary());
    Geometry decoded = codec.readBinary(wkb);
    assertThat(((LineString) decoded).getCoordinateSequence().hasZ()).isTrue();
    assertThat(((LineString) decoded).getCoordinateSequence().getZ(0)).isNaN();
  }
}
