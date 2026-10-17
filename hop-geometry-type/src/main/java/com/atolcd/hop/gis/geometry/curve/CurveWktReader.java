package com.atolcd.hop.gis.geometry.curve;

import com.atolcd.hop.gis.geometry.codec.CoordinateLayout;
import com.atolcd.hop.gis.geometry.codec.GeometryCodecErrorCode;
import com.atolcd.hop.gis.geometry.codec.GeometryCodecException;
import com.atolcd.hop.gis.geometry.codec.GeometryLayoutSupport;
import com.atolcd.hop.gis.geometry.codec.GeometryReadLimits;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.locationtech.jts.geom.Coordinate;
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

/**
 * Curved and linear WKT reader for the shared geometry model. Every parse uses a fresh token
 * cursor; declared coordinate layouts, exact curve controls and full input consumption are
 * enforced.
 */
public final class CurveWktReader {
  private final GeometryFactory factory;
  private final GeometryReadLimits limits;

  public CurveWktReader() {
    this(new GeometryFactory(), GeometryReadLimits.defaults());
  }

  public CurveWktReader(GeometryFactory factory) {
    this(factory, GeometryReadLimits.defaults());
  }

  public CurveWktReader(GeometryFactory factory, GeometryReadLimits limits) {
    this.factory = factory == null ? new GeometryFactory() : factory;
    this.limits = limits == null ? GeometryReadLimits.defaults() : limits;
  }

  public Geometry read(String wkt) throws GeometryCodecException {
    if (wkt == null || wkt.isBlank()) {
      return null;
    }
    String text = wkt.strip();
    WktTokenCursor in = new WktTokenCursor(text);
    ReadContext context = new ReadContext(limits);
    Geometry geometry = readGeometry(in, context, 1, null);
    in.expect(WktTokenCursor.Kind.EOF, "end of input");
    GeometryLayoutSupport.validateRepresentable(geometry, limits);
    return geometry;
  }

  private Geometry readGeometry(
      WktTokenCursor in, ReadContext context, int depth, CoordinateLayout inherited)
      throws GeometryCodecException {
    if (depth > limits.maxDepth()) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_LIMIT_EXCEEDED,
          "WKT nesting depth limit of " + limits.maxDepth() + " exceeded");
    }
    context.component();
    WktTokenCursor.Token typeToken = in.current();
    String typeWord = in.expectWord("Geometry type");
    GeometryType type = GeometryType.fromWord(typeWord);
    if (type == null) {
      if (GeometryType.isKnownUnsupported(typeWord)) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY,
            "Unsupported geometry type: " + typeWord,
            typeToken.offset());
      }
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_INVALID_WKT,
          "Unknown geometry type: " + typeWord,
          typeToken.offset());
    }
    CoordinateLayout marker = readOptionalLayout(in);
    CoordinateLayout layout = marker != null ? marker : inherited;
    if (in.wordIs("EMPTY")) {
      in.advance();
      return empty(type, layout);
    }
    return switch (type) {
      case POINT -> readPointBody(in, context, layout);
      case LINESTRING -> readLineBody(in, context, layout);
      case POLYGON -> readPolygonBody(in, context, layout);
      case MULTIPOINT -> readMultiPointBody(in, context, layout);
      case MULTILINESTRING -> readMultiLineStringBody(in, context, layout);
      case MULTIPOLYGON -> readMultiPolygonBody(in, context, layout);
      case GEOMETRYCOLLECTION -> readCollectionBody(in, context, depth, layout);
      case CIRCULARSTRING -> readCircularStringBody(in, context, layout);
      case COMPOUNDCURVE -> readCompoundCurveBody(in, context, depth, layout);
      case CURVEPOLYGON -> readCurvePolygonBody(in, context, depth, layout);
      case MULTICURVE -> readMultiCurveBody(in, context, depth, layout);
      case MULTISURFACE -> readMultiSurfaceBody(in, context, depth, layout);
    };
  }

  private CoordinateLayout readOptionalLayout(WktTokenCursor in) throws GeometryCodecException {
    if (!in.is(WktTokenCursor.Kind.WORD)) {
      return null;
    }
    String word = in.current().text();
    if (word.equalsIgnoreCase("Z")) {
      in.advance();
      return CoordinateLayout.XYZ;
    }
    if (word.equalsIgnoreCase("M")) {
      in.advance();
      return CoordinateLayout.XYM;
    }
    if (word.equalsIgnoreCase("ZM")) {
      in.advance();
      return CoordinateLayout.XYZM;
    }
    return null;
  }

  private Geometry empty(GeometryType type, CoordinateLayout layout) throws GeometryCodecException {
    CoordinateLayout actual = layout == null ? CoordinateLayout.XY : layout;
    return switch (type) {
      case POINT -> new Point(emptySequence(actual), factory);
      case LINESTRING -> factory.createLineString(emptySequence(actual));
      case POLYGON -> createEmptyPolygon(actual);
      case MULTIPOINT -> {
        requireXyEmpty(actual, type);
        yield factory.createMultiPoint(new Point[0]);
      }
      case MULTILINESTRING -> {
        requireXyEmpty(actual, type);
        yield factory.createMultiLineString(new LineString[0]);
      }
      case MULTIPOLYGON -> {
        requireXyEmpty(actual, type);
        yield factory.createMultiPolygon(new Polygon[0]);
      }
      case GEOMETRYCOLLECTION -> {
        requireXyEmpty(actual, type);
        yield factory.createGeometryCollection(new Geometry[0]);
      }
      case CIRCULARSTRING, COMPOUNDCURVE, CURVEPOLYGON ->
          throw unsupportedEmptyCurve(type);
      case MULTICURVE -> {
        requireXyEmpty(actual, type);
        yield new MultiCurve(List.of(), factory);
      }
      case MULTISURFACE -> {
        requireXyEmpty(actual, type);
        yield new MultiSurface(List.of(), factory);
      }
    };
  }

  private void requireXyEmpty(CoordinateLayout layout, GeometryType type)
      throws GeometryCodecException {
    if (layout != CoordinateLayout.XY) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT,
          "Empty " + type + " cannot retain declared layout " + layout);
    }
  }

  private GeometryCodecException unsupportedEmptyCurve(GeometryType type) {
    return new GeometryCodecException(
        GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE,
        "Empty " + type + " is not representable");
  }

  private Point readPointBody(
      WktTokenCursor in, ReadContext context, CoordinateLayout expected)
      throws GeometryCodecException {
    in.expect(WktTokenCursor.Kind.LPAREN, "(");
    if (in.is(WktTokenCursor.Kind.RPAREN)) {
      throw invalid("POINT requires one coordinate", in);
    }
    Coord coordinate = readCoordinateValues(in, expected, context);
    in.expect(WktTokenCursor.Kind.RPAREN, ")");
    return createPoint(coordinate);
  }

  private LineString readLineBody(
      WktTokenCursor in, ReadContext context, CoordinateLayout expected)
      throws GeometryCodecException {
    in.expect(WktTokenCursor.Kind.LPAREN, "(");
    SequenceData sequence = readSequenceValues(in, expected, context);
    requireLineSize(sequence, "LINESTRING");
    return factory.createLineString(createSequence(sequence));
  }

  private Polygon readPolygonBody(
      WktTokenCursor in, ReadContext context, CoordinateLayout expected)
      throws GeometryCodecException {
    in.expect(WktTokenCursor.Kind.LPAREN, "(");
    if (in.is(WktTokenCursor.Kind.RPAREN)) {
      throw invalid("POLYGON requires at least one ring", in);
    }
    CoordinateSequence shell = readRingSequence(in, context, expected);
    CoordinateLayout ringLayout = layoutOf(shell);
    List<LinearRing> holes = new ArrayList<>();
    while (in.is(WktTokenCursor.Kind.COMMA)) {
      in.advance();
      holes.add(createLinearRing(readRingSequence(in, context, ringLayout)));
    }
    in.expect(WktTokenCursor.Kind.RPAREN, ")");
    return factory.createPolygon(
        createLinearRing(shell), holes.toArray(LinearRing[]::new));
  }

  private CoordinateSequence readRingSequence(
      WktTokenCursor in, ReadContext context, CoordinateLayout expected)
      throws GeometryCodecException {
    in.expect(WktTokenCursor.Kind.LPAREN, "(");
    SequenceData sequence = readSequenceValues(in, expected, context);
    if (sequence.values().size() < 4) {
      throw invalid("Linear ring requires at least four coordinates", in);
    }
    CoordinateSequence coordinates = createSequence(sequence);
    if (!isClosed(coordinates)) {
      throw invalid("Linear ring is not closed", in);
    }
    return coordinates;
  }

  private CircularString readCircularStringBody(
      WktTokenCursor in, ReadContext context, CoordinateLayout expected)
      throws GeometryCodecException {
    in.expect(WktTokenCursor.Kind.LPAREN, "(");
    SequenceData sequence = readSequenceValues(in, expected, context);
    if (sequence.values().size() < 3 || sequence.values().size() % 2 == 0) {
      throw invalid("CIRCULARSTRING requires an odd number of at least three controls", in);
    }
    CoordinateSequence coordinates = createSequence(sequence);
    consumeCurveMaterialization(context, toCoordinates(coordinates));
    return new CircularString(coordinates, factory);
  }

  private CompoundCurve readCompoundCurveBody(
      WktTokenCursor in, ReadContext context, int depth, CoordinateLayout inherited)
      throws GeometryCodecException {
    in.expect(WktTokenCursor.Kind.LPAREN, "(");
    if (in.is(WktTokenCursor.Kind.RPAREN)) {
      throw invalid("COMPOUNDCURVE requires at least one component", in);
    }
    List<LineString> components = new ArrayList<>();
    LayoutState state = new LayoutState();
    Coordinate firstPoint = null;
    Coordinate previousEnd = null;
    while (true) {
      LineString component = readCurveMember(in, context, depth, memberLayout(state, inherited));
      Coordinate componentFirst = firstPoint(component);
      Coordinate componentLast = lastPoint(component);
      if (previousEnd != null && !same2D(previousEnd, componentFirst)) {
        throw invalid("COMPOUNDCURVE components are not connected", in);
      }
      if (firstPoint == null) {
        firstPoint = componentFirst;
      }
      previousEnd = componentLast;
      components.add(component);
      state.accept(GeometryLayoutSupport.primitiveLayout(component));
      if (in.is(WktTokenCursor.Kind.COMMA)) {
        in.advance();
        continue;
      }
      break;
    }
    in.expect(WktTokenCursor.Kind.RPAREN, ")");
    consumeCompoundMaterialization(context, components);
    return new CompoundCurve(components, factory);
  }

  private CurvePolygon readCurvePolygonBody(
      WktTokenCursor in, ReadContext context, int depth, CoordinateLayout inherited)
      throws GeometryCodecException {
    in.expect(WktTokenCursor.Kind.LPAREN, "(");
    if (in.is(WktTokenCursor.Kind.RPAREN)) {
      throw invalid("CURVEPOLYGON requires at least one ring", in);
    }
    List<LineString> rings = new ArrayList<>();
    LayoutState state = new LayoutState();
    while (true) {
      LineString ring = readCurveRing(in, context, depth, memberLayout(state, inherited));
      CoordinateLayout ringLayout = GeometryLayoutSupport.primitiveLayout(ring);
      if (!isClosed(ring)) {
        throw invalid("CURVEPOLYGON ring is not closed", in);
      }
      state.accept(ringLayout);
      rings.add(ring);
      if (in.is(WktTokenCursor.Kind.COMMA)) {
        in.advance();
        continue;
      }
      break;
    }
    in.expect(WktTokenCursor.Kind.RPAREN, ")");
    consumeCurvePolygonMaterialization(context, rings);
    return new CurvePolygon(rings, factory);
  }

  private LineString readCurveMember(
      WktTokenCursor in, ReadContext context, int depth, CoordinateLayout expected)
      throws GeometryCodecException {
    if (in.is(WktTokenCursor.Kind.LPAREN)) {
      return readLineBody(in, context, expected);
    }
    if (!in.is(WktTokenCursor.Kind.WORD)) {
      throw invalid("Curve member expected", in);
    }
    String word = in.current().text();
    in.advance();
    CoordinateLayout marker = readOptionalLayout(in);
    CoordinateLayout actual = marker != null ? marker : expected;
    if (marker != null && expected != null && marker != expected) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT,
          "Curve member layout " + marker + " conflicts with " + expected);
    }
    if (in.wordIs("EMPTY")) {
      in.advance();
      if (word.equalsIgnoreCase("LINESTRING")) {
        return factory.createLineString(emptySequence(actual == null ? CoordinateLayout.XY : actual));
      }
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE,
          "Empty " + word.toUpperCase(Locale.ROOT) + " is not representable");
    }
    if (word.equalsIgnoreCase("LINESTRING")) {
      return readLineBody(in, context, actual);
    }
    if (word.equalsIgnoreCase("CIRCULARSTRING")) {
      return readCircularStringBody(in, context, actual);
    }
    if (word.equalsIgnoreCase("COMPOUNDCURVE")) {
      return readCompoundCurveBody(in, context, depth + 1, actual);
    }
    throw invalid("Invalid curve member: " + word, in);
  }

  private LineString readCurveRing(
      WktTokenCursor in, ReadContext context, int depth, CoordinateLayout expected)
      throws GeometryCodecException {
    if (in.is(WktTokenCursor.Kind.LPAREN)) {
      in.advance();
      SequenceData sequence = readSequenceValues(in, expected, context);
      if (sequence.values().size() < 4) {
        throw invalid("CURVEPOLYGON ring requires at least four coordinates", in);
      }
      CoordinateSequence coordinates = createSequence(sequence);
      if (!isClosed(coordinates)) {
        throw invalid("CURVEPOLYGON ring is not closed", in);
      }
      return createLinearRing(coordinates);
    }
    if (!in.is(WktTokenCursor.Kind.WORD)) {
      throw invalid("Curve ring expected", in);
    }
    String word = in.current().text();
    in.advance();
    CoordinateLayout marker = readOptionalLayout(in);
    CoordinateLayout actual = marker != null ? marker : expected;
    if (marker != null && expected != null && marker != expected) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT,
          "Curve member layout " + marker + " conflicts with " + expected);
    }
    if (in.wordIs("EMPTY")) {
      in.advance();
      if (word.equalsIgnoreCase("LINESTRING")) {
        return factory.createLineString(emptySequence(actual == null ? CoordinateLayout.XY : actual));
      }
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE,
          "Empty " + word.toUpperCase(Locale.ROOT) + " is not representable");
    }
    if (word.equalsIgnoreCase("LINESTRING")) {
      LineString line = readLineBody(in, context, actual);
      if (!isClosed(line)) {
        throw invalid("CURVEPOLYGON ring is not closed", in);
      }
      return createLinearRingFromLine(line);
    }
    if (word.equalsIgnoreCase("CIRCULARSTRING")) {
      CircularString curve = readCircularStringBody(in, context, actual);
      if (!isClosed(curve)) {
        throw invalid("CURVEPOLYGON ring is not closed", in);
      }
      return curve;
    }
    if (word.equalsIgnoreCase("COMPOUNDCURVE")) {
      CompoundCurve curve = readCompoundCurveBody(in, context, depth + 1, actual);
      if (!isClosed(curve)) {
        throw invalid("CURVEPOLYGON ring is not closed", in);
      }
      return curve;
    }
    throw invalid("Invalid curve ring: " + word, in);
  }

  private MultiPoint readMultiPointBody(
      WktTokenCursor in, ReadContext context, CoordinateLayout inherited)
      throws GeometryCodecException {
    in.expect(WktTokenCursor.Kind.LPAREN, "(");
    if (in.is(WktTokenCursor.Kind.RPAREN)) {
      throw invalid("MULTIPOINT requires at least one member", in);
    }
    List<Point> points = new ArrayList<>();
    LayoutState state = new LayoutState();
    while (true) {
      Point point;
      if (in.wordIs("EMPTY")) {
        in.advance();
        CoordinateLayout layout = state.layout != null ? state.layout : inherited;
        layout = layout == null ? CoordinateLayout.XY : layout;
        state.accept(layout);
        point = new Point(emptySequence(layout), factory);
      } else if (in.wordIs("POINT")) {
        in.advance();
        CoordinateLayout marker = readOptionalLayout(in);
        CoordinateLayout expected = checkMarker(state, inherited, marker);
        in.expect(WktTokenCursor.Kind.LPAREN, "(");
        Coord coordinate = readCoordinateValues(in, expected, context);
        in.expect(WktTokenCursor.Kind.RPAREN, ")");
        state.accept(coordinate.layout());
        point = createPoint(coordinate);
      } else if (in.is(WktTokenCursor.Kind.LPAREN)) {
        in.advance();
        Coord coordinate =
            readCoordinateValues(in, state.layout != null ? state.layout : inherited, context);
        in.expect(WktTokenCursor.Kind.RPAREN, ")");
        state.accept(coordinate.layout());
        point = createPoint(coordinate);
      } else {
        Coord coordinate =
            readCoordinateValues(in, state.layout != null ? state.layout : inherited, context);
        state.accept(coordinate.layout());
        point = createPoint(coordinate);
      }
      points.add(point);
      if (in.is(WktTokenCursor.Kind.COMMA)) {
        in.advance();
        continue;
      }
      break;
    }
    in.expect(WktTokenCursor.Kind.RPAREN, ")");
    return factory.createMultiPoint(points.toArray(Point[]::new));
  }

  private MultiLineString readMultiLineStringBody(
      WktTokenCursor in, ReadContext context, CoordinateLayout inherited)
      throws GeometryCodecException {
    in.expect(WktTokenCursor.Kind.LPAREN, "(");
    if (in.is(WktTokenCursor.Kind.RPAREN)) {
      throw invalid("MULTILINESTRING requires at least one member", in);
    }
    List<LineString> lines = new ArrayList<>();
    LayoutState state = new LayoutState();
    while (true) {
      CoordinateLayout expected = state.layout != null ? state.layout : inherited;
      LineString line;
      if (in.wordIs("LINESTRING")) {
        in.advance();
        CoordinateLayout marker = readOptionalLayout(in);
        line = readLineBody(in, context, checkMarker(state, inherited, marker));
      } else if (in.is(WktTokenCursor.Kind.LPAREN)) {
        line = readLineBody(in, context, expected);
      } else {
        throw invalid("MULTILINESTRING member expected", in);
      }
      state.accept(GeometryLayoutSupport.primitiveLayout(line));
      lines.add(line);
      if (in.is(WktTokenCursor.Kind.COMMA)) {
        in.advance();
        continue;
      }
      break;
    }
    in.expect(WktTokenCursor.Kind.RPAREN, ")");
    return factory.createMultiLineString(lines.toArray(LineString[]::new));
  }

  private MultiPolygon readMultiPolygonBody(
      WktTokenCursor in, ReadContext context, CoordinateLayout inherited)
      throws GeometryCodecException {
    in.expect(WktTokenCursor.Kind.LPAREN, "(");
    if (in.is(WktTokenCursor.Kind.RPAREN)) {
      throw invalid("MULTIPOLYGON requires at least one member", in);
    }
    List<Polygon> polygons = new ArrayList<>();
    LayoutState state = new LayoutState();
    while (true) {
      CoordinateLayout expected = state.layout != null ? state.layout : inherited;
      Polygon polygon;
      if (in.wordIs("EMPTY")) {
        in.advance();
        CoordinateLayout layout = expected == null ? CoordinateLayout.XY : expected;
        state.accept(layout);
        polygon = createEmptyPolygon(layout);
      } else if (in.wordIs("POLYGON")) {
        in.advance();
        CoordinateLayout marker = readOptionalLayout(in);
        polygon = readPolygonBody(in, context, checkMarker(state, inherited, marker));
      } else if (in.is(WktTokenCursor.Kind.LPAREN)) {
        polygon = readPolygonBody(in, context, expected);
      } else {
        throw invalid("MULTIPOLYGON member expected", in);
      }
      state.accept(GeometryLayoutSupport.primitiveLayout(polygon));
      polygons.add(polygon);
      if (in.is(WktTokenCursor.Kind.COMMA)) {
        in.advance();
        continue;
      }
      break;
    }
    in.expect(WktTokenCursor.Kind.RPAREN, ")");
    return factory.createMultiPolygon(polygons.toArray(Polygon[]::new));
  }

  private MultiCurve readMultiCurveBody(
      WktTokenCursor in, ReadContext context, int depth, CoordinateLayout inherited)
      throws GeometryCodecException {
    in.expect(WktTokenCursor.Kind.LPAREN, "(");
    if (in.is(WktTokenCursor.Kind.RPAREN)) {
      throw invalid("MULTICURVE requires at least one member", in);
    }
    List<LineString> curves = new ArrayList<>();
    LayoutState state = new LayoutState();
    while (true) {
      LineString curve =
          readCurveMember(in, context, depth, memberLayout(state, inherited));
      curves.add(curve);
      if (in.is(WktTokenCursor.Kind.COMMA)) {
        in.advance();
        continue;
      }
      break;
    }
    in.expect(WktTokenCursor.Kind.RPAREN, ")");
    return new MultiCurve(curves, factory);
  }

  private MultiSurface readMultiSurfaceBody(
      WktTokenCursor in, ReadContext context, int depth, CoordinateLayout inherited)
      throws GeometryCodecException {
    in.expect(WktTokenCursor.Kind.LPAREN, "(");
    if (in.is(WktTokenCursor.Kind.RPAREN)) {
      throw invalid("MULTISURFACE requires at least one member", in);
    }
    List<Polygon> surfaces = new ArrayList<>();
    LayoutState state = new LayoutState();
    while (true) {
      CoordinateLayout expected = memberLayout(state, inherited);
      Polygon surface;
      if (in.wordIs("EMPTY")) {
        in.advance();
        CoordinateLayout layout = expected == null ? CoordinateLayout.XY : expected;
        state.accept(layout);
        surface = createEmptyPolygon(layout);
      } else if (in.wordIs("POLYGON")) {
        in.advance();
        CoordinateLayout marker = readOptionalLayout(in);
        surface = readPolygonBody(in, context, checkMarker(state, inherited, marker));
      } else if (in.wordIs("CURVEPOLYGON")) {
        in.advance();
        CoordinateLayout marker = readOptionalLayout(in);
        if (in.wordIs("EMPTY")) {
          in.advance();
          throw new GeometryCodecException(
              GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE,
              "Empty CURVEPOLYGON is not representable");
        }
        surface = readCurvePolygonBody(in, context, depth + 1, checkMarker(state, inherited, marker));
      } else if (in.is(WktTokenCursor.Kind.LPAREN)) {
        surface = readPolygonBody(in, context, expected);
      } else {
        throw invalid("MULTISURFACE member expected", in);
      }
      surfaces.add(surface);
      if (in.is(WktTokenCursor.Kind.COMMA)) {
        in.advance();
        continue;
      }
      break;
    }
    in.expect(WktTokenCursor.Kind.RPAREN, ")");
    return new MultiSurface(surfaces, factory);
  }

  private GeometryCollection readCollectionBody(
      WktTokenCursor in, ReadContext context, int depth, CoordinateLayout inherited)
      throws GeometryCodecException {
    in.expect(WktTokenCursor.Kind.LPAREN, "(");
    if (in.is(WktTokenCursor.Kind.RPAREN)) {
      throw invalid("GEOMETRYCOLLECTION requires at least one member", in);
    }
    List<Geometry> geometries = new ArrayList<>();
    while (true) {
      geometries.add(readGeometry(in, context, depth + 1, inherited));
      if (in.is(WktTokenCursor.Kind.COMMA)) {
        in.advance();
        continue;
      }
      break;
    }
    in.expect(WktTokenCursor.Kind.RPAREN, ")");
    return factory.createGeometryCollection(geometries.toArray(Geometry[]::new));
  }

  private Coord readCoordinateValues(
      WktTokenCursor in, CoordinateLayout expected, ReadContext context)
      throws GeometryCodecException {
    context.coordinate();
    double x = readOrdinate(in, OrdinateRole.X);
    double y = readOrdinate(in, OrdinateRole.Y);
    if (expected != null) {
      double z = expected.hasZ() ? readOrdinate(in, OrdinateRole.Z) : Double.NaN;
      double m = expected.hasM() ? readOrdinate(in, OrdinateRole.M) : Double.NaN;
      return new Coord(expected, x, y, z, m);
    }
    if (isNumberOrNan(in)) {
      double z = readOrdinate(in, OrdinateRole.Z);
      if (isNumberOrNan(in)) {
        throw invalid("Four unmarked ordinates require a ZM layout marker", in);
      }
      return new Coord(CoordinateLayout.XYZ, x, y, z, Double.NaN);
    }
    return new Coord(CoordinateLayout.XY, x, y, Double.NaN, Double.NaN);
  }

  private SequenceData readSequenceValues(
      WktTokenCursor in, CoordinateLayout expected, ReadContext context)
      throws GeometryCodecException {
    if (in.is(WktTokenCursor.Kind.RPAREN)) {
      throw invalid("Coordinate sequence must not be empty", in);
    }
    List<Coord> values = new ArrayList<>();
    Coord first = readCoordinateValues(in, expected, context);
    CoordinateLayout layout = first.layout();
    values.add(first);
    while (in.is(WktTokenCursor.Kind.COMMA)) {
      in.advance();
      Coord next = readCoordinateValues(in, layout, context);
      if (next.layout() != layout) {
        throw invalid("Inconsistent ordinate count in coordinate sequence", in);
      }
      values.add(next);
    }
    in.expect(WktTokenCursor.Kind.RPAREN, ")");
    return new SequenceData(layout, values);
  }

  private double readOrdinate(WktTokenCursor in, OrdinateRole role)
      throws GeometryCodecException {
    WktTokenCursor.Token token = in.current();
    if (token.kind() == WktTokenCursor.Kind.NAN) {
      if (role == OrdinateRole.X || role == OrdinateRole.Y) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_NONFINITE_COORDINATE,
            "NaN is only allowed for Z/M ordinates",
            token.offset());
      }
      in.advance();
      return Double.NaN;
    }
    if (token.kind() == WktTokenCursor.Kind.NUMBER) {
      return in.number(role.name());
    }
    if (token.kind() == WktTokenCursor.Kind.WORD
        && (token.text().equalsIgnoreCase("Infinity")
            || token.text().equalsIgnoreCase("Inf"))) {
      in.advance();
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_NONFINITE_COORDINATE,
          role.name() + " ordinate must be finite",
          token.offset());
    }
    throw invalid(role.name() + " ordinate expected", in);
  }

  private boolean isNumberOrNan(WktTokenCursor in) throws GeometryCodecException {
    return in.is(WktTokenCursor.Kind.NUMBER) || in.is(WktTokenCursor.Kind.NAN);
  }

  private CoordinateSequence createSequence(SequenceData data) {
    CoordinateSequence sequence =
        factory
            .getCoordinateSequenceFactory()
            .create(data.values().size(), data.layout().dimension(), data.layout().measures());
    for (int i = 0; i < data.values().size(); i++) {
      Coord value = data.values().get(i);
      sequence.setOrdinate(i, CoordinateSequence.X, value.x());
      sequence.setOrdinate(i, CoordinateSequence.Y, value.y());
      if (data.layout().hasZ()) {
        sequence.setOrdinate(i, data.layout().zIndex(), value.z());
      }
      if (data.layout().hasM()) {
        sequence.setOrdinate(i, data.layout().mIndex(), value.m());
      }
    }
    return sequence;
  }

  private Point createPoint(Coord coordinate) {
    CoordinateSequence sequence =
        factory
            .getCoordinateSequenceFactory()
            .create(1, coordinate.layout().dimension(), coordinate.layout().measures());
    sequence.setOrdinate(0, CoordinateSequence.X, coordinate.x());
    sequence.setOrdinate(0, CoordinateSequence.Y, coordinate.y());
    if (coordinate.layout().hasZ()) {
      sequence.setOrdinate(0, coordinate.layout().zIndex(), coordinate.z());
    }
    if (coordinate.layout().hasM()) {
      sequence.setOrdinate(0, coordinate.layout().mIndex(), coordinate.m());
    }
    return factory.createPoint(sequence);
  }

  private CoordinateSequence emptySequence(CoordinateLayout layout) {
    return factory
        .getCoordinateSequenceFactory()
        .create(0, layout.dimension(), layout.measures());
  }

  private Polygon createEmptyPolygon(CoordinateLayout layout) {
    LinearRing ring = new LinearRing(emptySequence(layout), factory);
    return new Polygon(ring, null, factory);
  }

  private LinearRing createLinearRing(CoordinateSequence sequence) {
    return new LinearRing(sequence, factory);
  }

  private LinearRing createLinearRingFromLine(LineString line) {
    return new LinearRing(line.getCoordinateSequence().copy(), factory);
  }

  private CoordinateLayout layoutOf(CoordinateSequence sequence) throws GeometryCodecException {
    return GeometryLayoutSupport.layoutOf(sequence);
  }

  private void requireLineSize(SequenceData sequence, String role) throws GeometryCodecException {
    if (sequence.values().size() == 1) {
      throw invalid("Non-empty " + role + " requires at least two coordinates", null);
    }
    if (sequence.values().isEmpty()) {
      throw invalid("Coordinate sequence must not be empty", null);
    }
  }

  private static boolean isClosed(CoordinateSequence sequence) {
    int last = sequence.size() - 1;
    return sequence.size() >= 4
        && same2D(
            sequence.getX(0),
            sequence.getY(0),
            sequence.getX(last),
            sequence.getY(last));
  }

  private static boolean isClosed(LineString line) {
    if (line.isEmpty()) {
      return false;
    }
    Coordinate first = firstPoint(line);
    Coordinate last = lastPoint(line);
    return same2D(first, last);
  }

  private static Coordinate firstPoint(LineString line) {
    if (line instanceof CircularString circularString) {
      return circularString.getControlPoints()[0];
    }
    if (line instanceof CompoundCurve compoundCurve) {
      return firstPoint(compoundCurve.getComponents().get(0));
    }
    return line.getCoordinateN(0);
  }

  private static Coordinate lastPoint(LineString line) {
    if (line instanceof CircularString circularString) {
      Coordinate[] controls = circularString.getControlPoints();
      return controls[controls.length - 1];
    }
    if (line instanceof CompoundCurve compoundCurve) {
      List<LineString> components = compoundCurve.getComponents();
      return lastPoint(components.get(components.size() - 1));
    }
    return line.getCoordinateN(line.getNumPoints() - 1);
  }

  private static boolean same2D(Coordinate first, Coordinate second) {
    return same2D(first.x, first.y, second.x, second.y);
  }

  private static boolean same2D(double x1, double y1, double x2, double y2) {
    return Double.compare(x1, x2) == 0 && Double.compare(y1, y2) == 0;
  }

  private CoordinateLayout memberLayout(LayoutState state, CoordinateLayout inherited) {
    return state.layout != null ? state.layout : inherited;
  }

  private CoordinateLayout checkMarker(
      LayoutState state, CoordinateLayout inherited, CoordinateLayout marker)
      throws GeometryCodecException {
    if (marker == null) {
      return memberLayout(state, inherited);
    }
    if (state.layout != null && state.layout != marker) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT,
          "Inconsistent coordinate layouts in multi/collection member");
    }
    if (inherited != null && inherited != marker) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT,
          "Member layout " + marker + " conflicts with inherited layout " + inherited);
    }
    return marker;
  }

  private void consumeCurveMaterialization(ReadContext context, Coordinate[] controls)
      throws GeometryCodecException {
    long remaining = context.remainingCurveVertices();
    long total = 1;
    for (int i = 0; i + 2 < controls.length; i += 2) {
      long available = remaining - (total - 1);
      if (available < 0) {
        throw limitExceeded();
      }
      long count;
      try {
        count =
            new ArcSegment(controls[i], controls[i + 1], controls[i + 2])
                .linearizedCoordinateCount(CircularString.DEFAULT_MAX_ERROR, available);
      } catch (IllegalArgumentException e) {
        throw limitExceeded();
      }
      if (count > available) {
        throw limitExceeded();
      }
      total += count - 1;
    }
    context.curveVertices(total);
  }

  private void consumeCompoundMaterialization(ReadContext context, List<LineString> components)
      throws GeometryCodecException {
    long total = 0;
    for (LineString component : components) {
      total = addCount(total, exactCoordinateCount(component));
    }
    context.curveVertices(total);
  }

  private void consumeCurvePolygonMaterialization(ReadContext context, List<LineString> rings)
      throws GeometryCodecException {
    long total = 0;
    for (LineString ring : rings) {
      total = addCount(total, exactCoordinateCount(ring));
    }
    context.curveVertices(total);
  }

  private long exactCoordinateCount(LineString line) throws GeometryCodecException {
    if (line instanceof CircularString circularString) {
      return circularString.getControlPointSequence().size();
    }
    if (line instanceof CompoundCurve compoundCurve) {
      long total = 0;
      for (LineString component : compoundCurve.getComponents()) {
        total = addCount(total, exactCoordinateCount(component));
      }
      return total;
    }
    return line.getNumPoints();
  }

  private static long addCount(long left, long right) throws GeometryCodecException {
    try {
      return Math.addExact(left, right);
    } catch (ArithmeticException e) {
      throw limitExceeded();
    }
  }

  private static GeometryCodecException limitExceeded() {
    return new GeometryCodecException(
        GeometryCodecErrorCode.GC_LIMIT_EXCEEDED, "Curve materialization budget exceeded");
  }

  private static Coordinate[] toCoordinates(CoordinateSequence sequence) {
    Coordinate[] coordinates = new Coordinate[sequence.size()];
    for (int i = 0; i < coordinates.length; i++) {
      coordinates[i] = sequence.getCoordinate(i);
    }
    return coordinates;
  }

  private GeometryCodecException invalid(String message, WktTokenCursor in)
      throws GeometryCodecException {
    int offset = in == null ? -1 : in.current().offset();
    return new GeometryCodecException(GeometryCodecErrorCode.GC_INVALID_WKT, message, offset);
  }

  private record Coord(CoordinateLayout layout, double x, double y, double z, double m) {}

  private record SequenceData(CoordinateLayout layout, List<Coord> values) {}

  private static final class LayoutState {
    private CoordinateLayout layout;

    private void accept(CoordinateLayout next) throws GeometryCodecException {
      if (layout == null) {
        layout = next;
      } else if (layout != next) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT,
            "Inconsistent coordinate layouts in multi/collection: " + layout + " and " + next);
      }
    }
  }

  private static final class ReadContext {
    private final GeometryReadLimits limits;
    private long coordinates;
    private long components;
    private long curveVertices;

    private ReadContext(GeometryReadLimits limits) {
      this.limits = limits;
    }

    private void component() throws GeometryCodecException {
      components++;
      if (components > limits.maxComponents()) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_LIMIT_EXCEEDED,
            "Geometry component limit of " + limits.maxComponents() + " exceeded");
      }
    }

    private void coordinate() throws GeometryCodecException {
      coordinates++;
      if (coordinates > limits.maxCoordinates()) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_LIMIT_EXCEEDED,
            "Coordinate limit of " + limits.maxCoordinates() + " exceeded");
      }
    }

    private long remainingCurveVertices() {
      return Math.max(0, limits.maxMaterializedCurveVertices() - curveVertices);
    }

    private void curveVertices(long count) throws GeometryCodecException {
      curveVertices += count;
      if (curveVertices > limits.maxMaterializedCurveVertices()) {
        throw limitExceeded();
      }
    }
  }

  private enum OrdinateRole {
    X,
    Y,
    Z,
    M
  }

  private enum GeometryType {
    POINT,
    LINESTRING,
    POLYGON,
    MULTIPOINT,
    MULTILINESTRING,
    MULTIPOLYGON,
    GEOMETRYCOLLECTION,
    CIRCULARSTRING,
    COMPOUNDCURVE,
    CURVEPOLYGON,
    MULTICURVE,
    MULTISURFACE;

    private static GeometryType fromWord(String word) {
      try {
        return valueOf(word.toUpperCase(Locale.ROOT));
      } catch (IllegalArgumentException e) {
        return null;
      }
    }

    private static boolean isKnownUnsupported(String word) {
      String upper = word.toUpperCase(Locale.ROOT);
      return upper.equals("TIN") || upper.equals("TRIANGLE") || upper.equals("POLYHEDRALSURFACE");
    }
  }
}
