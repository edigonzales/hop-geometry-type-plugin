package com.atolcd.hop.gis.geometry.codec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceConfigurationError;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.geotools.api.referencing.NoSuchAuthorityCodeException;
import org.geotools.api.referencing.crs.CoordinateReferenceSystem;
import org.geotools.api.referencing.crs.GeographicCRS;
import org.geotools.api.referencing.crs.ProjectedCRS;
import org.geotools.api.referencing.crs.VerticalCRS;
import org.geotools.referencing.CRS;
import tech.units.indriya.function.Calculus;
import tech.units.indriya.function.DefaultNumberSystem;

/** Authority lookup and axis permutations only; never a coordinate transformation. */
final class GeoJsonCrs {
  static final String CRS84 = "http://www.opengis.net/def/crs/OGC/0/CRS84";
  static final String CRS84H = "http://www.opengis.net/def/crs/OGC/0/CRS84h";
  private static final Pattern EPSG = Pattern.compile("(?i)(?:EPSG:|urn:ogc:def:crs:EPSG:[^:]*:|https?://www\\.opengis\\.net/def/crs/EPSG/[^/]+/)([0-9]+)");
  private static final Map<String, Resolved> CACHE = new LinkedHashMap<>(64, .75f, true) {
    @Override protected boolean removeEldestEntry(Map.Entry<String, Resolved> eldest) { return size() > 128; }
  };
  private GeoJsonCrs() {}

  record Resolved(Object descriptor, int srid, int dimension, int xIndex, int yIndex,
                  boolean wgs84, boolean vertical) {}

  static Resolved resolve(Object descriptor, GeoJsonProfile profile) throws GeometryCodecException {
    if (descriptor instanceof List<?> parts) {
      if (parts.size() != 2) throw unsupported("Only a horizontal/vertical CRS pair is supported");
      Resolved horizontal = resolve(parts.get(0), profile);
      Resolved vertical = resolve(parts.get(1), profile);
      if (horizontal.vertical() || horizontal.dimension() != 2 || !vertical.vertical())
        throw unsupported("Compound CRS must contain a 2D horizontal CRS followed by a vertical CRS");
      return new Resolved(List.copyOf(parts), horizontal.srid(), 3, horizontal.xIndex(), horizontal.yIndex(), false, false);
    }
    String value = reference(descriptor);
    if (value.equalsIgnoreCase("OGC:CRS84") || value.equalsIgnoreCase("CRS84")
        || value.matches("(?i)(?:https?://www\\.opengis\\.net/def/crs/OGC/[^/]+/|urn:ogc:def:crs:OGC:[^:]*:)CRS84"))
      return new Resolved(descriptor, 4326, 2, 0, 1, true, false);
    if (value.equalsIgnoreCase("OGC:CRS84h") || value.equalsIgnoreCase("CRS84h")
        || value.matches("(?i)(?:https?://www\\.opengis\\.net/def/crs/OGC/[^/]+/|urn:ogc:def:crs:OGC:[^:]*:)CRS84h"))
      return new Resolved(descriptor, 4979, 3, 0, 1, true, false);
    Matcher match = EPSG.matcher(value);
    if (!match.matches()) throw unsupported("Unsupported CRS reference: " + value);
    int srid;
    try { srid = Integer.parseInt(match.group(1)); }
    catch (NumberFormatException e) { throw unsupported("EPSG code is out of range"); }
    if (srid <= 0) throw unsupported("EPSG code must be positive");
    String key = srid + ":" + (profile == GeoJsonProfile.JSONFG10);
    synchronized (CACHE) {
      Resolved cached = CACHE.get(key);
      if (cached != null) return new Resolved(descriptor, cached.srid(), cached.dimension(), cached.xIndex(), cached.yIndex(), cached.wgs84(), cached.vertical());
    }
    Resolved resolved = decode(descriptor, srid, profile == GeoJsonProfile.JSONFG10);
    synchronized (CACHE) {
      // Cache authority metadata only, never caller-supplied JSON objects or strings.
      CACHE.put(key,new Resolved(null,resolved.srid(),resolved.dimension(),resolved.xIndex(),
          resolved.yIndex(),resolved.wgs84(),resolved.vertical()));
    }
    return resolved;
  }

  static String reference(Object descriptor) throws GeometryCodecException {
    if (descriptor instanceof String s && !s.isBlank()) return s;
    if (descriptor instanceof Map<?,?> map) {
      if (map.containsKey("epoch")) throw unsupported("CRS epochs are not supported");
      if ("Reference".equals(map.get("type")) && map.get("href") instanceof String href) return href;
      if ("name".equals(map.get("type")) && map.get("properties") instanceof Map<?,?> properties
          && properties.get("name") instanceof String name) return name;
    }
    throw unsupported("Expected an EPSG or OGC CRS84 reference");
  }

  private static Resolved decode(Object descriptor, int srid, boolean authorityAxes)
      throws GeometryCodecException {
    Thread thread = Thread.currentThread();
    ClassLoader previous = thread.getContextClassLoader();
    try {
      thread.setContextClassLoader(GeoJsonCrs.class.getClassLoader());
      try { Calculus.currentNumberSystem(); }
      catch (IllegalArgumentException | ServiceConfigurationError e) {
        Calculus.setCurrentNumberSystem(new DefaultNumberSystem());
      }
      CoordinateReferenceSystem crs = CRS.decode("EPSG:" + srid, !authorityAxes);
      int dimension = crs.getCoordinateSystem().getDimension();
      if (crs instanceof VerticalCRS) return new Resolved(descriptor, srid, 1, -1, -1, false, true);
      if (!(crs instanceof GeographicCRS || crs instanceof ProjectedCRS)
          || (dimension != 2 && dimension != 3)) throw unsupported("CRS must be a 2D/3D geographic or projected CRS");
      // GeoTools' force-XY CRS also handles polar meridians and westing/southing.
      // Matching complete axes avoids confusing two NORTH/SOUTH polar directions.
      CoordinateReferenceSystem xy = authorityAxes ? CRS.decode("EPSG:" + srid, true) : crs;
      int x = -1, y = -1;
      for (int i = 0; i < dimension; i++) {
        Object axis = crs.getCoordinateSystem().getAxis(i);
        if (CRS.equalsIgnoreMetadata(axis, xy.getCoordinateSystem().getAxis(0))) x = i;
        if (CRS.equalsIgnoreMetadata(axis, xy.getCoordinateSystem().getAxis(1))) y = i;
      }
      if (x < 0 || y < 0 || x == y || x > 1 || y > 1)
        throw unsupported("CRS axes cannot be mapped unambiguously to native X/Y");
      return new Resolved(descriptor, srid, dimension, x, y, srid == 4326 || srid == 4979, false);
    } catch (GeometryCodecException e) { throw e;
    } catch (NoSuchAuthorityCodeException e) {
      throw new GeometryCodecException(GeometryCodecErrorCode.GC_UNSUPPORTED_CRS, "Unknown EPSG:" + srid, -1, e);
    } catch (Exception e) {
      throw new IllegalStateException("Unable to initialize or use the shared EPSG authority runtime", e);
    } catch (LinkageError | ServiceConfigurationError e) {
      throw new IllegalStateException("Shared GeoTools referencing runtime is unavailable", e);
    } finally { thread.setContextClassLoader(previous); }
  }

  static GeometryCodecException unsupported(String message) {
    return new GeometryCodecException(GeometryCodecErrorCode.GC_UNSUPPORTED_CRS, message);
  }
}
