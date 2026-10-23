package com.atolcd.hop.gis.geometry.codec;

import com.atolcd.hop.gis.geometry.curve.ArcSegment;
import com.atolcd.hop.gis.geometry.curve.CircularString;
import com.atolcd.hop.gis.geometry.curve.CompoundCurve;
import com.atolcd.hop.gis.geometry.curve.CurvePolygon;
import com.atolcd.hop.gis.geometry.curve.MultiCurve;
import com.atolcd.hop.gis.geometry.curve.MultiSurface;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.algorithm.Orientation;
import org.locationtech.jts.geom.CoordinateSequence;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;

/** Strict implementation shared by Geometry, Feature and file consumers. */
final class GeoJsonGeometryCodec {
  private final GeometryFactory factory;
  private final GeometryReadLimits limits;
  private long coordinates, components, materialized;
  private boolean readZ;
  private boolean writeMeasured;

  GeoJsonGeometryCodec(GeometryFactory factory, GeometryReadLimits limits) {
    this.factory = factory;
    this.limits = limits;
  }

  GeoJsonReadResult read(Map<String,Object> object, GeoJsonReadOptions options)
      throws GeometryCodecException {
    if (object == null) return new GeoJsonReadResult(null, options.context());
    GeoJsonProfile detected = GeoJsonProfile.detect(object, options.context());
    GeoJsonProfile profile = options.profile() == GeoJsonProfile.AUTO ? detected : options.profile();
    if (profile != GeoJsonProfile.JSONFG10 && detected == GeoJsonProfile.JSONFG10
        && object.containsKey("conformsTo"))
      throw invalid("JSON-FG conformance conflicts with the explicitly selected profile");
    if (profile == GeoJsonProfile.RFC7946 && (object.containsKey("crs") || object.containsKey("coordRefSys") || object.containsKey("measures")))
      throw invalid("RFC 7946 does not accept a CRS or measures member");
    if (profile == GeoJsonProfile.JSONFG10 && object.containsKey("crs"))
      throw invalid("JSON-FG must not contain a conflicting Legacy crs member");
    if (profile == GeoJsonProfile.LEGACY2008 && (object.containsKey("coordRefSys") || object.containsKey("measures")))
      throw invalid("JSON-FG metadata is not Legacy GeoJSON");
    if (profile == GeoJsonProfile.JSONFG10 && options.context().profile() != GeoJsonProfile.JSONFG10
        && !object.containsKey("conformsTo"))
      throw invalid("A JSON-FG root Geometry requires its core conformsTo declaration");
    GeoJsonContext context = context(object, profile, options.context());
    GeoJsonCrs.Resolved crs = GeoJsonCrs.resolve(context.coordRefSys(), profile);
    if (crs.vertical()) throw GeoJsonCrs.unsupported("A vertical CRS cannot be used alone");
    Geometry geometry;
    try { geometry = readGeometry(object, profile, crs, enabled(context.measures()), 1); }
    catch (GeometryCodecException e) { throw e;
    } catch (IllegalArgumentException e) { throw new GeometryCodecException(GeometryCodecErrorCode.GC_INVALID_JSON, "Invalid geometry structure", -1, e); }
    if (profile == GeoJsonProfile.RFC7946 && readZ)
      context = new GeoJsonContext(profile, GeoJsonCrs.CRS84H, Map.of());
    GeometrySridSupport.setSridRecursively(geometry, context.coordRefSys().equals(GeoJsonCrs.CRS84H) ? 4979 : crs.srid());
    GeometryLayoutSupport.validateRepresentable(geometry, limits);
    validateConformance(object, geometry, profile, context);
    if (profile == GeoJsonProfile.JSONFG10) validateLinearTopology(geometry);
    return new GeoJsonReadResult(geometry, context);
  }

  Map<String,Object> write(Geometry geometry, GeoJsonWriteOptions options)
      throws GeometryCodecException {
    if (geometry == null) return null;
    validateWriteDepth(geometry);
    GeometryLayoutSupport.validateRepresentable(geometry, limits);
    int srid = GeometrySridSupport.resolveConsistentSrid(geometry);
    GeoJsonProfile profile = options.profile();
    CoordinateLayout layout = exactLayout(geometry);
    boolean z = layout.hasZ() && !options.dropZ();
    boolean emptyCollection=geometry.getClass()==GeometryCollection.class && geometry.getNumGeometries()==0;
    boolean m = (layout.hasM() || (emptyCollection && enabled(options.context().measures()))) && !options.dropM();
    if (m && profile != GeoJsonProfile.JSONFG10)
      throw loss("M values require JSON-FG or the explicit drop M option");
    if (profile != GeoJsonProfile.JSONFG10 && !options.dropM() && !options.context().measures().isEmpty())
      throw loss("Measure metadata requires JSON-FG or the explicit drop M option");
    if (profile == GeoJsonProfile.RFC7946 && emptyCollection && srid == 4979 && !options.dropZ())
      throw loss("An EMPTY 3D collection needs JSON-FG/Legacy metadata or an explicit drop Z option");
    GeoJsonContext requested = options.context();
    Object descriptor = requested.coordRefSys();
    if (descriptor == null) {
      if (profile == GeoJsonProfile.RFC7946) {
        if (srid != 4326 && srid != 4979)
          throw GeoJsonCrs.unsupported("RFC 7946 requires an explicit WGS84 SRID/context");
        if (z && srid != 4979)
          throw GeoJsonCrs.unsupported("A WGS84 ellipsoidal height CRS is required for Z");
        descriptor = z ? GeoJsonCrs.CRS84H : GeoJsonCrs.CRS84;
      } else {
        if (srid <= 0) throw GeoJsonCrs.unsupported("A positive EPSG SRID or explicit CRS context is required");
        descriptor = profile == GeoJsonProfile.LEGACY2008 ? "EPSG:" + srid
            : "http://www.opengis.net/def/crs/EPSG/0/" + srid;
      }
    }
    GeoJsonCrs.Resolved crs = GeoJsonCrs.resolve(descriptor, profile);
    if (crs.vertical()) throw GeoJsonCrs.unsupported("A vertical CRS cannot be used alone");
    if (srid <= 0 || (srid != crs.srid() && !(crs.wgs84() && (srid == 4326 || srid == 4979))))
      throw GeoJsonCrs.unsupported("Geometry SRID is missing or conflicts with the CRS context");
    if (profile == GeoJsonProfile.RFC7946 && !crs.wgs84())
      throw GeoJsonCrs.unsupported("RFC 7946 requires WGS84 coordinates; reproject explicitly first");
    if (profile == GeoJsonProfile.RFC7946 && z && crs.dimension() != 3)
      throw GeoJsonCrs.unsupported("A WGS84 ellipsoidal height CRS is required for Z");
    if (profile == GeoJsonProfile.RFC7946)
      crs = GeoJsonCrs.resolve(z ? GeoJsonCrs.CRS84H : GeoJsonCrs.CRS84, profile);
    if (profile != GeoJsonProfile.LEGACY2008 && z && crs.dimension() != 3)
      throw GeoJsonCrs.unsupported("A 3D or horizontal/vertical compound CRS is required for Z");
    if (profile == GeoJsonProfile.JSONFG10 && !z && crs.dimension() == 3 && !emptyCollection) {
      if (!options.dropZ()) throw GeoJsonCrs.unsupported("3D CRS requires a Z ordinate");
      if (descriptor instanceof List<?> parts) descriptor = parts.get(0);
      else if (crs.srid() == 4979) descriptor = GeoJsonCrs.CRS84;
      else throw GeoJsonCrs.unsupported("Choose a 2D CRS context when dropping Z");
      crs = GeoJsonCrs.resolve(descriptor, profile);
    }
    Map<String,Object> measures = !options.dropM() && !requested.measures().isEmpty()
        ? measureContext(requested.measures()) : m ? Map.of("enabled",true) : Map.of();
    if (m && !enabled(measures)) throw invalid("M ordinates require measures.enabled=true");
    if (!m && Boolean.TRUE.equals(requested.measures().get("enabled")) && !options.dropM())
      throw invalid("Measures context requires an M ordinate");
    if (profile == GeoJsonProfile.JSONFG10) validateLinearTopology(geometry);
    writeMeasured = m;
    Map<String,Object> result = writeGeometry(geometry, profile, crs, options, 1);
    if (result == null) return null;
    if (profile == GeoJsonProfile.LEGACY2008) {
      result.put("crs", Map.of("type", "name", "properties", Map.of("name", GeoJsonCrs.reference(descriptor))));
    } else if (profile == GeoJsonProfile.JSONFG10) {
      result.put("coordRefSys", descriptor);
      if (!measures.isEmpty()) result.put("measures", measures);
      List<Object> conforms = new ArrayList<>();
      conforms.add(GeoJsonContext.JSONFG_CORE);
      if (hasCurves(geometry)) conforms.add(GeoJsonContext.JSONFG_CURVES);
      if (!measures.isEmpty()) conforms.add(GeoJsonContext.JSONFG_MEASURES);
      result.put("conformsTo", conforms);
    }
    return result;
  }

  private GeoJsonContext context(Map<String,Object> object, GeoJsonProfile profile,
      GeoJsonContext inherited) throws GeometryCodecException {
    Object descriptor;
    Map<String,Object> measures = Map.of();
    if (profile == GeoJsonProfile.RFC7946) descriptor = GeoJsonCrs.CRS84;
    else if (profile == GeoJsonProfile.LEGACY2008) {
      descriptor = object.containsKey("crs") ? object.get("crs") : inherited.coordRefSys();
      if (object.containsKey("crs") && descriptor == null) throw GeoJsonCrs.unsupported("Legacy crs:null has an unknown coordinate reference system");
      if (descriptor == null) descriptor = GeoJsonCrs.CRS84;
    } else {
      descriptor = object.containsKey("coordRefSys") ? object.get("coordRefSys") : inherited.coordRefSys();
      if (object.containsKey("coordRefSys") && descriptor == null)
        throw GeoJsonCrs.unsupported("JSON-FG coordRefSys must not be null");
      measures = object.containsKey("measures") ? object(object.get("measures"), "measures") : inherited.measures();
      if (!measures.isEmpty()) measureContext(measures);
      if (descriptor == null) {
        int dimension=firstPositionDimension(object,1)-(enabled(measures)?1:0);
        descriptor=dimension==3?GeoJsonCrs.CRS84H:GeoJsonCrs.CRS84;
      }
    }
    return new GeoJsonContext(profile, descriptor, measures);
  }

  private int firstPositionDimension(Map<String,Object> object,int depth) throws GeometryCodecException {
    if (!limits.allows(depth)) throw limit("Geometry nesting budget exceeded");
    Object coordinates=object.get("coordinates");
    if (coordinates instanceof List<?> list) {
      int dimension=positionDimension(list);
      if(dimension>0) return dimension;
    }
    Object geometries=object.get("geometries");
    if(geometries instanceof List<?> list) for(Object item:list) {
      int dimension=firstPositionDimension(object(item,"geometry"),depth+1);
      if(dimension>0) return dimension;
    }
    return 0;
  }
  private int positionDimension(List<?> values) throws GeometryCodecException {
    return positionDimension(values,1);
  }
  private int positionDimension(List<?> values,int depth) throws GeometryCodecException {
    // Constant bounded recursion, without queuing a large coordinate array.
    if(depth>4) throw invalid("Invalid coordinate array nesting");
    if(!values.isEmpty() && values.getFirst() instanceof Number) return values.size();
    for(Object item:values) if(item instanceof List<?> list) {
      int dimension=positionDimension(list,depth+1);
      if(dimension>0) return dimension;
    }
    return 0;
  }

  private Geometry readGeometry(Map<String,Object> object, GeoJsonProfile profile,
      GeoJsonCrs.Resolved crs, boolean measured, int depth) throws GeometryCodecException {
    component(depth);
    if (profile == GeoJsonProfile.RFC7946 && (object.containsKey("crs") || object.containsKey("coordRefSys") || object.containsKey("measures")))
      throw invalid("RFC 7946 must not contain CRS or measure metadata");
    if (profile == GeoJsonProfile.JSONFG10 && object.containsKey("crs"))
      throw invalid("JSON-FG must not contain a conflicting Legacy crs member");
    if (profile == GeoJsonProfile.LEGACY2008 && (object.containsKey("coordRefSys") || object.containsKey("measures")))
      throw invalid("JSON-FG metadata is not Legacy GeoJSON");
    String type = string(object.get("type"), "Geometry type");
    if (type.equals("Feature") || type.equals("FeatureCollection"))
      throw invalid("Use the GeoJSON Feature transforms or Vector Reader for Features and FeatureCollections");
    java.util.Set<String> allowed = java.util.Set.of("type","coordinates","geometries","bbox","crs","coordRefSys","measures","conformsTo");
    for (String member : object.keySet())
      if (!allowed.contains(member)) throw invalid("Unsupported Geometry member would be lost: " + member);
    if (depth > 1 && profile == GeoJsonProfile.JSONFG10
        && (object.containsKey("coordRefSys") || object.containsKey("measures") || object.containsKey("conformsTo")))
      throw invalid("Embedded JSON-FG geometry must inherit CRS, measures and conformance");
    if (depth > 1 && profile == GeoJsonProfile.LEGACY2008 && object.containsKey("crs")) {
      GeoJsonCrs.Resolved own = GeoJsonCrs.resolve(object.get("crs"), profile);
      if (own.srid() != crs.srid()) throw new GeometryCodecException(GeometryCodecErrorCode.GC_MIXED_SRID, "Embedded geometry has a different CRS");
    }
    boolean usesGeometries=type.equals("GeometryCollection") || type.equals("CompoundCurve")
        || type.equals("CurvePolygon") || type.equals("MultiCurve") || type.equals("MultiSurface");
    if ((usesGeometries && object.containsKey("coordinates")) || (!usesGeometries && object.containsKey("geometries")))
      throw invalid("Geometry contains an unexpected coordinates/geometries member");
    if (isCurveType(type) && profile != GeoJsonProfile.JSONFG10)
      throw invalid("True curves require JSON-FG; linearize explicitly first");
    return switch (type) {
      case "Point" -> factory.createPoint(sequence(array(object.get("coordinates"), "coordinates"), profile, crs, measured, true));
      case "LineString" -> factory.createLineString(sequence(array(object.get("coordinates"), "coordinates"), profile, crs, measured, false));
      case "Polygon" -> polygon(array(object.get("coordinates"), "coordinates"), profile, crs, measured, depth);
      case "MultiPoint" -> {
        List<?> list = array(object.get("coordinates"), "coordinates");
        children(list.size(), depth + 1);
        Point[] values = new Point[list.size()];
        for (int i = 0; i < values.length; i++) values[i] = factory.createPoint(sequence(array(list.get(i), "position"), profile, crs, measured, true));
        yield factory.createMultiPoint(values);
      }
      case "MultiLineString" -> {
        List<?> list = array(object.get("coordinates"), "coordinates"); children(list.size(), depth + 1);
        LineString[] values = new LineString[list.size()];
        for (int i = 0; i < values.length; i++) values[i] = factory.createLineString(sequence(array(list.get(i), "line"), profile, crs, measured, false));
        yield factory.createMultiLineString(values);
      }
      case "MultiPolygon" -> {
        List<?> list = array(object.get("coordinates"), "coordinates"); children(list.size(), depth + 1);
        Polygon[] values = new Polygon[list.size()];
        for (int i = 0; i < values.length; i++) values[i] = polygon(array(list.get(i), "polygon"), profile, crs, measured, depth + 1);
        yield factory.createMultiPolygon(values);
      }
      case "GeometryCollection" -> {
        List<?> list = array(object.get("geometries"), "geometries");
        preflightChildren(list.size(), depth + 1);
        Geometry[] values = new Geometry[list.size()];
        for (int i = 0; i < values.length; i++) {
          Map<String,Object> child = object(list.get(i), "geometry");
          if (profile == GeoJsonProfile.JSONFG10 && ("GeometryCollection".equals(child.get("type")) || isCurveType(String.valueOf(child.get("type")))))
            throw invalid("JSON-FG GeometryCollection supports only non-nested Simple Feature geometries");
          values[i] = readGeometry(child, profile, crs, measured, depth + 1);
        }
        yield factory.createGeometryCollection(values);
      }
      case "CircularString" -> circular(sequence(array(object.get("coordinates"), "coordinates"), profile, crs, measured, false));
      case "CompoundCurve", "CurvePolygon", "MultiCurve", "MultiSurface" -> {
        List<?> list = array(object.get("geometries"), "geometries");
        if (list.isEmpty()) throw invalid(type + " requires at least one component");
        preflightChildren(list.size(), depth + 1);
        List<Geometry> parts = new ArrayList<>(list.size());
        for (Object item : list) parts.add(readGeometry(object(item, "geometry"), profile, crs, measured, depth + 1));
        yield curvedCollection(type, parts);
      }
      default -> throw new GeometryCodecException(GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY, "Unsupported GeoJSON geometry type: " + type);
    };
  }

  private Polygon polygon(List<?> rings, GeoJsonProfile profile, GeoJsonCrs.Resolved crs,
      boolean measured, int depth) throws GeometryCodecException {
    children(rings.size(), depth + 1);
    if (rings.isEmpty()) return factory.createPolygon(factory.createLinearRing(emptySequence(crs, measured)));
    LinearRing shell = factory.createLinearRing(sequence(array(rings.get(0), "ring"), profile, crs, measured, false));
    LinearRing[] holes = new LinearRing[rings.size() - 1];
    for (int i = 0; i < holes.length; i++) holes[i] = factory.createLinearRing(sequence(array(rings.get(i + 1), "ring"), profile, crs, measured, false));
    if (shell.isEmpty() || java.util.Arrays.stream(holes).anyMatch(Geometry::isEmpty)) throw invalid("Polygon rings must not be empty");
    if (!closed(shell,false,false)) throw invalid("Polygon rings must be closed in every ordinate");
    for (LinearRing hole:holes) if(!closed(hole,false,false)) throw invalid("Polygon rings must be closed in every ordinate");
    return factory.createPolygon(shell, holes);
  }

  private CoordinateSequence sequence(List<?> input, GeoJsonProfile profile,
      GeoJsonCrs.Resolved crs, boolean measured, boolean point) throws GeometryCodecException {
    List<?> positions = point ? (input.isEmpty() ? List.of() : List.of(input)) : input;
    if (coordinates + positions.size() > limits.maxCoordinates()) throw limit("Coordinate budget exceeded");
    coordinates += positions.size();
    if (positions.isEmpty()) return emptySequence(crs, measured);
    int dimension = array(positions.get(0), "position").size();
    int expected = crs.dimension() + (measured ? 1 : 0);
    if ((profile == GeoJsonProfile.JSONFG10 && dimension != expected)
        || (profile != GeoJsonProfile.JSONFG10 && dimension != 2 && dimension != 3))
      throw invalid("Position dimension does not match the CRS and measures context");
    boolean z = dimension - (measured ? 1 : 0) == 3;
    readZ |= z;
    CoordinateSequence result = factory.getCoordinateSequenceFactory().create(positions.size(), dimension, measured ? 1 : 0);
    for (int i = 0; i < positions.size(); i++) {
      List<?> position = array(positions.get(i), "position");
      if (position.size() != dimension) throw invalid("Coordinate sequence contains mixed dimensions");
      for (int j = 0; j < dimension; j++) {
        int source = j == 0 ? crs.xIndex() : j == 1 ? crs.yIndex() : j;
        double value = number(position.get(source));
        if (profile == GeoJsonProfile.RFC7946 && ((j == 0 && (value < -180 || value > 180)) || (j == 1 && (value < -90 || value > 90))))
          throw invalid("RFC 7946 longitude/latitude is outside the supported range");
        result.setOrdinate(i, j, value);
      }
    }
    return result;
  }

  private CoordinateSequence emptySequence(GeoJsonCrs.Resolved crs, boolean measured) {
    return factory.getCoordinateSequenceFactory().create(0, crs.dimension() + (measured ? 1 : 0), measured ? 1 : 0);
  }

  private CircularString circular(CoordinateSequence sequence) throws GeometryCodecException {
    if (sequence.size() < 3 || sequence.size() > 11 || sequence.size() % 2 == 0)
      throw invalid("JSON-FG CircularString requires 3, 5, 7, 9 or 11 control points");
    for (int i = 0; i + 2 < sequence.size(); i += 2) {
      var a = sequence.getCoordinate(i); var b = sequence.getCoordinate(i + 1); var c = sequence.getCoordinate(i + 2);
      if (a.equals2D(b) || a.equals2D(c) || b.equals2D(c) || Orientation.index(a,b,c) == 0)
        throw invalid("JSON-FG circular arc control points must be distinct and non-collinear");
      long available = limits.maxMaterializedCurveVertices() - materialized;
      long count = new ArcSegment(a,b,c).linearizedCoordinateCount(CircularString.DEFAULT_MAX_ERROR, available);
      if (count > available) throw limit("Curve materialization budget exceeded");
      materialized += count;
    }
    return new CircularString(sequence, factory);
  }

  private Geometry curvedCollection(String type, List<Geometry> parts) throws GeometryCodecException {
    if (type.equals("MultiSurface")) {
      List<Polygon> surfaces = new ArrayList<>();
      for (Geometry part : parts) {
        if (!(part instanceof Polygon)) throw invalid("MultiSurface members must be Polygon or CurvePolygon");
        surfaces.add((Polygon)part);
      }
      return new MultiSurface(surfaces, factory);
    }
    List<LineString> curves = new ArrayList<>();
    for (Geometry part : parts) {
      if (!(part instanceof LineString) || part instanceof LinearRing || (type.equals("CompoundCurve") && part instanceof CompoundCurve))
        throw invalid(type + " members must be permitted curve types");
      curves.add((LineString)part);
    }
    if (type.equals("CompoundCurve")) {
      for (int i = 1; i < curves.size(); i++)
        if (!joined(curves.get(i - 1), curves.get(i), false, false))
          throw invalid("CompoundCurve components must join consecutively");
      return new CompoundCurve(curves, factory);
    }
    if (type.equals("CurvePolygon")) {
      for (LineString ring : curves) if (!closed(ring,false,false)) throw invalid("CurvePolygon rings must be closed in every ordinate");
      return new CurvePolygon(curves, factory);
    }
    return new MultiCurve(curves, factory);
  }

  private Map<String,Object> writeGeometry(Geometry geometry, GeoJsonProfile profile,
      GeoJsonCrs.Resolved crs, GeoJsonWriteOptions options, int depth) throws GeometryCodecException {
    if (!limits.allows(depth)) throw limit("Geometry nesting budget exceeded");
    String type = type(geometry);
    if (isCurveType(type) && profile != GeoJsonProfile.JSONFG10)
      throw invalid("True curves require JSON-FG; linearize explicitly first");
    boolean emptyCollection = geometry.getClass() == GeometryCollection.class && geometry.getNumGeometries() == 0;
    if (geometry.isEmpty() && !emptyCollection) {
      if (options.emptyToNull()) return null;
      throw loss("EMPTY geometry requires the explicit EMPTY to NULL option");
    }
    Map<String,Object> result = new LinkedHashMap<>();
    result.put("type", type);
    if (geometry instanceof CircularString circular) {
      CoordinateSequence controls = circular.getControlPointSequence();
      // Validate the JSON-FG representation without materializing another curve.
      validateCircularForWrite(controls);
      result.put("coordinates", positions(controls, profile, crs, options, false));
    } else if (geometry instanceof CompoundCurve curve) {
      requireConsecutive(curve.getComponents(),options);
      result.put("geometries", writeParts(curve.getComponents(), profile, crs, options, depth));
    } else if (geometry instanceof CurvePolygon polygon) {
      for (LineString ring : polygon.getCurveRings()) if (!closed(ring,options.dropZ(),options.dropM())) throw invalid("CurvePolygon rings must be closed in every output ordinate");
      result.put("geometries", writeParts(polygon.getCurveRings(), profile, crs, options, depth));
    } else if (geometry instanceof MultiCurve multi) {
      result.put("geometries", writeParts(multi.getCurves(), profile, crs, options, depth));
    } else if (geometry instanceof MultiSurface multi) {
      result.put("geometries", writeParts(multi.getSurfaces(), profile, crs, options, depth));
    } else if (geometry instanceof Point point) {
      result.put("coordinates", positions(point.getCoordinateSequence(), profile, crs, options, false).get(0));
    } else if (geometry instanceof LineString line) {
      result.put("coordinates", positions(line.getCoordinateSequence(), profile, crs, options, false));
    } else if (geometry instanceof Polygon polygon) {
      List<Object> rings = new ArrayList<>();
      rings.add(positions(polygon.getExteriorRing().getCoordinateSequence(), profile, crs, options, true));
      for (int i = 0; i < polygon.getNumInteriorRing(); i++)
        rings.add(positions(polygon.getInteriorRingN(i).getCoordinateSequence(), profile, crs, options, false, true));
      result.put("coordinates", rings);
    } else if (geometry instanceof MultiPoint || geometry instanceof MultiLineString || geometry instanceof MultiPolygon) {
      List<Object> parts = new ArrayList<>();
      for (int i = 0; i < geometry.getNumGeometries(); i++) {
        Map<String,Object> member = writeGeometry(geometry.getGeometryN(i), profile, crs, options, depth + 1);
        if (member == null) throw loss("An EMPTY member cannot become NULL inside a multi-geometry");
        parts.add(member.get("coordinates"));
      }
      result.put("coordinates", parts);
    } else if (geometry instanceof GeometryCollection collection) {
      List<Object> parts = new ArrayList<>();
      for (int i = 0; i < collection.getNumGeometries(); i++) {
        Geometry member = collection.getGeometryN(i);
        if (profile == GeoJsonProfile.JSONFG10 && (member.getClass() == GeometryCollection.class || hasCurves(member)))
          throw invalid("JSON-FG GeometryCollection supports only non-nested Simple Feature geometries");
        Map<String,Object> part = writeGeometry(member, profile, crs, options, depth + 1);
        if (part == null) throw loss("An EMPTY member cannot become NULL inside a GeometryCollection");
        parts.add(part);
      }
      result.put("geometries", parts);
    } else throw invalid("Unsupported geometry class");
    return result;
  }

  private List<Object> writeParts(List<? extends Geometry> parts, GeoJsonProfile profile,
      GeoJsonCrs.Resolved crs, GeoJsonWriteOptions options, int depth) throws GeometryCodecException {
    if (parts.isEmpty()) throw invalid("JSON-FG curved collections require at least one component");
    List<Object> result = new ArrayList<>();
    for (Geometry part : parts) {
      Map<String,Object> value = writeGeometry(part, profile, crs, options, depth + 1);
      if (value == null) throw loss("EMPTY components cannot become NULL inside a curved geometry");
      result.add(value);
    }
    return result;
  }

  private List<Object> positions(CoordinateSequence sequence, GeoJsonProfile profile,
      GeoJsonCrs.Resolved crs, GeoJsonWriteOptions options, boolean outer) throws GeometryCodecException {
    return positions(sequence, profile, crs, options, outer, false);
  }

  private List<Object> positions(CoordinateSequence sequence, GeoJsonProfile profile,
      GeoJsonCrs.Resolved crs, GeoJsonWriteOptions options, boolean outer, boolean inner)
      throws GeometryCodecException {
    CoordinateLayout layout = GeometryLayoutSupport.layoutOf(sequence);
    boolean z = layout.hasZ() && !options.dropZ();
    boolean m = layout.hasM() && !options.dropM();
    if (profile == GeoJsonProfile.JSONFG10 && (crs.dimension() != (z ? 3 : 2) || m != writeMeasured))
      throw invalid("All JSON-FG positions must match the CRS and measures dimensions");
    if ((outer || inner) && sequence.size() > 0
        && !samePosition(sequence,sequence.size()-1,sequence,0,options.dropZ(),options.dropM()))
      throw invalid("Polygon rings must be closed in every output ordinate");
    boolean reverse = false;
    if (profile == GeoJsonProfile.RFC7946 && (outer || inner) && sequence.size() > 0) {
      boolean ccw = Orientation.isCCW(sequence);
      reverse = outer ? !ccw : ccw;
    }
    List<Object> result = new ArrayList<>(sequence.size());
    for (int step = 0; step < sequence.size(); step++) {
      int i = reverse ? sequence.size() - step - 1 : step;
      double x = finite(sequence.getX(i)), y = finite(sequence.getY(i));
      if (profile == GeoJsonProfile.RFC7946 && (x < -180 || x > 180 || y < -90 || y > 90))
        throw invalid("RFC 7946 longitude/latitude is outside the supported range");
      List<Object> position = new ArrayList<>(2 + (z ? 1 : 0) + (m ? 1 : 0));
      position.add(crs.xIndex() == 0 ? x : y);
      position.add(crs.xIndex() == 0 ? y : x);
      if (z) position.add(finite(sequence.getOrdinate(i, layout.zIndex())));
      if (m) position.add(finite(sequence.getOrdinate(i, layout.mIndex())));
      result.add(position);
    }
    return result;
  }

  private void validateCircularForWrite(CoordinateSequence sequence) throws GeometryCodecException {
    if (sequence.size() < 3 || sequence.size() > 11 || sequence.size() % 2 == 0)
      throw invalid("JSON-FG CircularString requires 3, 5, 7, 9 or 11 control points");
    for (int i = 0; i + 2 < sequence.size(); i += 2) {
      var a = sequence.getCoordinate(i); var b = sequence.getCoordinate(i + 1); var c = sequence.getCoordinate(i + 2);
      if (a.equals2D(b) || a.equals2D(c) || b.equals2D(c) || Orientation.index(a,b,c) == 0)
        throw invalid("JSON-FG circular arc control points must be distinct and non-collinear");
    }
  }

  private static void requireConsecutive(List<LineString> curves, GeoJsonWriteOptions options) throws GeometryCodecException {
    for (int i = 1; i < curves.size(); i++)
      if (curves.get(i - 1) instanceof CompoundCurve || curves.get(i) instanceof CompoundCurve
          || !joined(curves.get(i - 1),curves.get(i),options.dropZ(),options.dropM()))
        throw invalid("CompoundCurve requires consecutive LineString/CircularString components");
  }

  private static CoordinateLayout exactLayout(Geometry geometry) throws GeometryCodecException {
    if (geometry instanceof CircularString curve) return GeometryLayoutSupport.layoutOf(curve.getControlPointSequence());
    if (geometry instanceof CompoundCurve curve) return union(curve.getComponents());
    if (geometry instanceof CurvePolygon polygon) return union(polygon.getCurveRings());
    if (geometry instanceof Point point) return GeometryLayoutSupport.layoutOf(point.getCoordinateSequence());
    if (geometry instanceof LineString line) return GeometryLayoutSupport.layoutOf(line.getCoordinateSequence());
    if (geometry instanceof Polygon polygon) {
      List<Geometry> rings = new ArrayList<>(); rings.add(polygon.getExteriorRing());
      for (int i=0;i<polygon.getNumInteriorRing();i++) rings.add(polygon.getInteriorRingN(i));
      return union(rings);
    }
    if (geometry instanceof GeometryCollection collection) {
      boolean z=false,m=false;
      for (int i=0;i<collection.getNumGeometries();i++) {
        CoordinateLayout layout=exactLayout(collection.getGeometryN(i)); z|=layout.hasZ();m|=layout.hasM();
      }
      return CoordinateLayout.of(z,m);
    }
    throw invalid("Unsupported Geometry layout");
  }
  private static CoordinateLayout union(List<? extends Geometry> parts) throws GeometryCodecException {
    boolean z=false,m=false;
    for (Geometry part:parts) {CoordinateLayout layout=exactLayout(part);z|=layout.hasZ();m|=layout.hasM();}
    return CoordinateLayout.of(z,m);
  }
  private static CoordinateSequence firstSequence(LineString curve) {
    if (curve instanceof CircularString circular) return circular.getControlPointSequence();
    if (curve instanceof CompoundCurve compound) return firstSequence(compound.getComponents().getFirst());
    return curve.getCoordinateSequence();
  }
  private static CoordinateSequence lastSequence(LineString curve) {
    if (curve instanceof CircularString circular) return circular.getControlPointSequence();
    if (curve instanceof CompoundCurve compound) return lastSequence(compound.getComponents().getLast());
    return curve.getCoordinateSequence();
  }
  private static boolean joined(LineString left,LineString right,boolean dropZ,boolean dropM)
      throws GeometryCodecException {
    CoordinateSequence end=lastSequence(left), start=firstSequence(right);
    return end.size()>0 && start.size()>0 && samePosition(end,end.size()-1,start,0,dropZ,dropM);
  }
  private static boolean closed(LineString ring,boolean dropZ,boolean dropM) throws GeometryCodecException {
    CoordinateSequence end=lastSequence(ring), start=firstSequence(ring);
    return end.size()>0 && start.size()>0 && samePosition(end,end.size()-1,start,0,dropZ,dropM);
  }
  private static boolean samePosition(CoordinateSequence a,int ai,CoordinateSequence b,int bi,
      boolean dropZ,boolean dropM) throws GeometryCodecException {
    CoordinateLayout left=GeometryLayoutSupport.layoutOf(a),right=GeometryLayoutSupport.layoutOf(b);
    if (a.getX(ai)!=b.getX(bi) || a.getY(ai)!=b.getY(bi)) return false;
    if (!dropZ && (left.hasZ()!=right.hasZ() || (left.hasZ() && a.getZ(ai)!=b.getZ(bi)))) return false;
    return dropM || (left.hasM()==right.hasM() && (!left.hasM() || a.getM(ai)==b.getM(bi)));
  }
  private static void validateLinearTopology(Geometry geometry) throws GeometryCodecException {
    if (!hasCurves(geometry)) {
      var error=new org.locationtech.jts.operation.valid.IsValidOp(geometry).getValidationError();
      if (error!=null) throw invalid("Invalid JSON-FG Simple Feature geometry: "+error.getMessage());
    } else if (geometry instanceof CompoundCurve curve) {
      for (LineString part:curve.getComponents()) validateLinearTopology(part);
    } else if (geometry instanceof CurvePolygon polygon) {
      for (LineString ring:polygon.getCurveRings()) validateLinearTopology(ring);
    } else if (geometry instanceof MultiCurve curves) {
      for (LineString part:curves.getCurves()) validateLinearTopology(part);
    } else if (geometry instanceof MultiSurface surfaces) {
      for (Polygon part:surfaces.getSurfaces()) validateLinearTopology(part);
    }
  }

  private void validateWriteDepth(Geometry geometry) throws GeometryCodecException {
    record Entry(Geometry geometry, int depth) {}
    java.util.ArrayDeque<Entry> pending = new java.util.ArrayDeque<>();
    pending.push(new Entry(geometry,1));
    long count = 0;
    while (!pending.isEmpty()) {
      Entry entry = pending.pop();
      if (!limits.allows(entry.depth()) || ++count > limits.maxComponents())
        throw limit("Geometry component/depth budget exceeded");
      Geometry part = entry.geometry();
      List<? extends Geometry> children = null;
      if (part instanceof CompoundCurve curve) children = curve.getComponents();
      else if (part instanceof CurvePolygon polygon) children = polygon.getCurveRings();
      else if (part instanceof GeometryCollection collection) {
        // Check size before pushing children; never allocate a list for the whole collection.
        if (count + pending.size() + collection.getNumGeometries() > limits.maxComponents())
          throw limit("Geometry component budget exceeded");
        for (int i = 0; i < collection.getNumGeometries(); i++)
          pending.push(new Entry(collection.getGeometryN(i),entry.depth()+1));
      }
      if (children != null) {
        if (count + pending.size() + children.size() > limits.maxComponents())
          throw limit("Geometry component budget exceeded");
        for (Geometry child : children) pending.push(new Entry(child,entry.depth()+1));
      }
    }
  }

  private static Map<String,Object> measureContext(Map<String,Object> context) throws GeometryCodecException {
    if (context.isEmpty()) return Map.of("enabled", true);
    if (!(context.get("enabled") instanceof Boolean)) throw invalid("measures.enabled must be boolean");
    if (context.containsKey("unit") && !(context.get("unit") instanceof String)) throw invalid("measures.unit must be text");
    if (context.containsKey("description") && !(context.get("description") instanceof String)) throw invalid("measures.description must be text");
    return context;
  }

  private static boolean enabled(Map<String,Object> measures) { return Boolean.TRUE.equals(measures.get("enabled")); }

  private static void validateConformance(Map<String,Object> object, Geometry geometry,
      GeoJsonProfile profile, GeoJsonContext context) throws GeometryCodecException {
    if (profile != GeoJsonProfile.JSONFG10 || !object.containsKey("conformsTo")) return;
    List<?> list = array(object.get("conformsTo"), "conformsTo");
    for (Object value:list) if(!(value instanceof String)) throw invalid("conformsTo entries must be strings");
    if (!list.contains(GeoJsonContext.JSONFG_CORE)) throw invalid("JSON-FG conformance must include core");
    if (hasCurves(geometry) && !list.contains(GeoJsonContext.JSONFG_CURVES)) throw invalid("Curves require circular-arcs conformance");
    if (!context.measures().isEmpty() && !list.contains(GeoJsonContext.JSONFG_MEASURES)) throw invalid("M ordinates require measures conformance");
  }

  private static boolean hasCurves(Geometry geometry) {
    if (geometry instanceof CircularString || geometry instanceof CompoundCurve || geometry instanceof CurvePolygon
        || geometry instanceof MultiCurve || geometry instanceof MultiSurface) return true;
    if (geometry instanceof GeometryCollection collection)
      for (int i = 0; i < collection.getNumGeometries(); i++) if (hasCurves(collection.getGeometryN(i))) return true;
    return false;
  }

  private static String type(Geometry geometry) {
    if (geometry instanceof CircularString) return "CircularString";
    if (geometry instanceof CompoundCurve) return "CompoundCurve";
    if (geometry instanceof CurvePolygon) return "CurvePolygon";
    if (geometry instanceof MultiCurve) return "MultiCurve";
    if (geometry instanceof MultiSurface) return "MultiSurface";
    if (geometry instanceof LinearRing) return "LineString";
    return geometry.getGeometryType();
  }

  private static boolean isCurveType(String type) {
    return type.equals("CircularString") || type.equals("CompoundCurve") || type.equals("CurvePolygon")
        || type.equals("MultiCurve") || type.equals("MultiSurface");
  }

  private void component(int depth) throws GeometryCodecException {
    if (!limits.allows(depth) || ++components > limits.maxComponents()) throw limit("Geometry component/depth budget exceeded");
  }
  private void preflightChildren(int count, int depth) throws GeometryCodecException {
    if (!limits.allows(depth) || components + count > limits.maxComponents()) throw limit("Geometry component/depth budget exceeded");
  }
  private void children(int count, int depth) throws GeometryCodecException {
    preflightChildren(count, depth); components += count;
  }

  private static List<?> array(Object value, String name) throws GeometryCodecException {
    if (!(value instanceof List<?> list)) throw invalid(name + " must be an array");
    return list;
  }
  private static Map<String,Object> object(Object value, String name) throws GeometryCodecException {
    if (!(value instanceof Map<?,?>)) throw invalid(name + " must be an object");
    @SuppressWarnings("unchecked") Map<String,Object> result = (Map<String,Object>)value;
    return result;
  }
  private static String string(Object value, String name) throws GeometryCodecException {
    if (!(value instanceof String text)) throw invalid(name + " must be text");
    return text;
  }
  private static double number(Object value) throws GeometryCodecException {
    if (!(value instanceof Number number)) throw invalid("Position ordinate must be numeric");
    return finite(number.doubleValue());
  }
  private static double finite(double value) throws GeometryCodecException {
    if (!Double.isFinite(value)) throw new GeometryCodecException(GeometryCodecErrorCode.GC_NONFINITE_COORDINATE, "JSON coordinates must be finite");
    return value;
  }
  private static GeometryCodecException invalid(String message) {
    return new GeometryCodecException(GeometryCodecErrorCode.GC_INVALID_JSON, message);
  }
  private static GeometryCodecException limit(String message) {
    return new GeometryCodecException(GeometryCodecErrorCode.GC_LIMIT_EXCEEDED, message);
  }
  private static GeometryCodecException loss(String message) {
    return new GeometryCodecException(GeometryCodecErrorCode.GC_LOSSY_CONVERSION, message);
  }
}
