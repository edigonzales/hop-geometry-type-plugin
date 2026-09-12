package com.atolcd.hop.gis.geometry.curve;

import java.util.List;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;

/** SQL/MM MULTICURVE represented as a JTS MultiLineString plus exact curve components. */
public final class MultiCurve extends MultiLineString {
  private final List<LineString> curves;

  public MultiCurve(List<? extends LineString> curves, GeometryFactory factory) {
    super(curves.toArray(LineString[]::new), factory);
    this.curves = List.copyOf(curves);
  }

  public List<LineString> getCurves() {
    return curves;
  }

  @Override
  protected MultiCurve copyInternal() {
    return new MultiCurve(curves.stream().map(c -> (LineString) c.copy()).toList(), getFactory());
  }

  @Override
  protected MultiCurve reverseInternal() {
    return new MultiCurve(
        curves.stream().map(c -> (LineString) c.reverse()).toList(), getFactory());
  }
}
