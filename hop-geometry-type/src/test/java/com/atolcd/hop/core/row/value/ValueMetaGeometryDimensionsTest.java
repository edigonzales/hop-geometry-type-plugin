package com.atolcd.hop.core.row.value;

import static org.assertj.core.api.Assertions.*;

import com.atolcd.hop.gis.geometry.curve.*;
import java.io.*;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.*;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;

class ValueMetaGeometryDimensionsTest {
  @Test
  void serializationAndCopiesRetainAllOrdinates() throws Exception {
    for (int dimension : new int[] {2, 3, 4})
      for (int measures : new int[] {0, 1}) {
        if (dimension - measures < 2 || dimension - measures > 3) continue;
        for (boolean missing : new boolean[] {false, true}) {
          var sequence =
              PackedCoordinateSequenceFactory.DOUBLE_FACTORY.create(2, dimension, measures);
          for (int i = 0; i < 2; i++) {
            sequence.setOrdinate(i, 0, i + 1);
            sequence.setOrdinate(i, 1, i + 2);
            for (int j = 2; j < dimension; j++)
              sequence.setOrdinate(i, j, missing ? Double.NaN : j + 10);
          }
          Geometry geometry =
              new GeometryFactory(new PrecisionModel(), 2056).createLineString(sequence);
          var meta = new ValueMetaGeometry("g");
          ByteArrayOutputStream bytes = new ByteArrayOutputStream();
          meta.writeData(new DataOutputStream(bytes), geometry);
          Geometry loaded =
              (Geometry)
                  meta.readData(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())));
          for (Geometry result :
              new Geometry[] {loaded, (Geometry) meta.cloneValueData(geometry)}) {
            var actual = ((LineString) result).getCoordinateSequence();
            assertThat(actual.getDimension()).isEqualTo(dimension);
            assertThat(actual.getMeasures()).isEqualTo(measures);
            assertThat(result.getSRID()).isEqualTo(2056);
            for (int i = 0; i < 2; i++)
              for (int j = 0; j < dimension; j++)
                if (Double.isNaN(sequence.getOrdinate(i, j)))
                  assertThat(actual.getOrdinate(i, j)).isNaN();
                else assertThat(actual.getOrdinate(i, j)).isEqualTo(sequence.getOrdinate(i, j));
          }
          if (!missing && measures == 1) assertThat(meta.getString(geometry)).contains("M");
        }
      }
  }

  @Test
  void emptyAndLegacyXyRemainReadable() throws Exception {
    var seq = PackedCoordinateSequenceFactory.DOUBLE_FACTORY.create(0, 4, 1);
    var g = new GeometryFactory(new PrecisionModel(), 2056).createPoint(seq);
    var copy = CurveGeometrySupport.readWkb(CurveGeometrySupport.writeWkb(g));
    assertThat(copy.isEmpty()).isTrue();
    assertThat(((Point) copy).getCoordinateSequence().getMeasures()).isEqualTo(1);
    var old =
        new org.locationtech.jts.io.WKBWriter(2)
            .write(new GeometryFactory().createPoint(new CoordinateXY(1, 2)));
    assertThat(CurveGeometrySupport.readWkb(old).getCoordinate().x).isEqualTo(1);
  }
}
