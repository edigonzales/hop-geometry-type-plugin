package com.atolcd.hop.gis.geometry.curve;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

/** SQL/MM COMPOUNDCURVE represented as a JTS LineString plus exact components. */
public final class CompoundCurve extends LineString {
  private final List<LineString> components;

  public CompoundCurve(List<? extends LineString> components, GeometryFactory factory) {
    super(factory.getCoordinateSequenceFactory().create(linearize(components)), factory);
    if (components.isEmpty()) {
      throw new IllegalArgumentException("COMPOUNDCURVE requires at least one component");
    }
    this.components = List.copyOf(components);
  }

  public List<LineString> getComponents() {
    return components;
  }

  private static Coordinate[] linearize(List<? extends LineString> components) {
    List<Coordinate> coordinates = new ArrayList<>();
    for (LineString component : components) {
      Coordinate[] current = component.getCoordinates();
      for (int i = 0; i < current.length; i++) {
        if (!coordinates.isEmpty() && i == 0) {
          continue;
        }
        coordinates.add(current[i].copy());
      }
    }
    return coordinates.toArray(Coordinate[]::new);
  }

  @Override
  protected CompoundCurve copyInternal() {
    return new CompoundCurve(
        components.stream().map(c -> (LineString) c.copy()).toList(), getFactory());
  }

  @Override
  protected CompoundCurve reverseInternal() {
    List<LineString> result = new ArrayList<>();
    for (int i = components.size() - 1; i >= 0; i--)
      result.add((LineString) components.get(i).reverse());
    return new CompoundCurve(result, getFactory());
  }
}
