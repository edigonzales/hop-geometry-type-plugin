package com.atolcd.hop.gis.geometry.curve;

import java.util.List;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

/** SQL/MM MULTISURFACE represented as a JTS MultiPolygon plus exact surface components. */
public final class MultiSurface extends MultiPolygon {
  private final List<Polygon> surfaces;

  public MultiSurface(List<? extends Polygon> surfaces, GeometryFactory factory) {
    super(surfaces.toArray(Polygon[]::new), factory);
    this.surfaces = List.copyOf(surfaces);
  }

  public List<Polygon> getSurfaces() {
    return surfaces;
  }
}
