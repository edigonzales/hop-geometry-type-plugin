package com.atolcd.hop.gis.geometry.curve;

import com.atolcd.hop.gis.geometry.codec.CoordinateLayout;
import com.atolcd.hop.gis.geometry.codec.GeometryCodecErrorCode;
import com.atolcd.hop.gis.geometry.codec.GeometryCodecException;
import com.atolcd.hop.gis.geometry.codec.GeometryLayoutSupport;
import com.atolcd.hop.gis.geometry.codec.GeometryReadLimits;
import com.atolcd.hop.gis.geometry.codec.GeometrySridSupport;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
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
import org.locationtech.jts.geom.PrecisionModel;
import org.locationtech.jts.geom.impl.PackedCoordinateSequenceFactory;

/**
 * Strict WKB/EWKB reader for linear members and SQL/MM curve types 1 through 12. Both ISO/SQL-MM
 * dimension offsets and EWKB flags are understood, but their mixed use is rejected.
 */
public final class CurveWkbReader {
  public static final int WKB_POINT = 1;
  public static final int WKB_LINESTRING = 2;
  public static final int WKB_POLYGON = 3;
  public static final int WKB_MULTIPOINT = 4;
  public static final int WKB_MULTILINESTRING = 5;
  public static final int WKB_MULTIPOLYGON = 6;
  public static final int WKB_GEOMETRYCOLLECTION = 7;
  public static final int WKB_CIRCULARSTRING = 8;
  public static final int WKB_COMPOUNDCURVE = 9;
  public static final int WKB_CURVEPOLYGON = 10;
  public static final int WKB_MULTICURVE = 11;
  public static final int WKB_MULTISURFACE = 12;

  static final int EWKB_Z = 0x80000000;
  static final int EWKB_M = 0x40000000;
  static final int EWKB_SRID = 0x20000000;
  static final int EWKB_BBOX = 0x10000000;
  static final int EWKB_TYPE_MASK = 0x1FFFFFFF;

  private final GeometryFactory factory;
  private final GeometryReadLimits limits;

  public CurveWkbReader() {
    this(
        new GeometryFactory(
            new PrecisionModel(), 0, PackedCoordinateSequenceFactory.DOUBLE_FACTORY),
        GeometryReadLimits.defaults());
  }

  public CurveWkbReader(GeometryFactory factory) {
    this(factory, GeometryReadLimits.defaults());
  }

  public CurveWkbReader(GeometryFactory factory, GeometryReadLimits limits) {
    this.factory = factory == null ? new GeometryFactory() : factory;
    this.limits = limits == null ? GeometryReadLimits.defaults() : limits;
  }

  /**
   * Source-compatible unchecked entry point. Existing callers keep receiving
   * {@link IllegalArgumentException}; the shared codec uses {@link #readChecked(byte[])}.
   */
  public Geometry read(byte[] wkb) {
    if (wkb == null) {
      throw new IllegalArgumentException("WKB must not be null");
    }
    try {
      return readChecked(wkb);
    } catch (GeometryCodecException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  /** Checked entry point for the shared codec. */
  public Geometry readChecked(byte[] wkb) throws GeometryCodecException {
    if (wkb == null) {
      return null;
    }
    if (wkb.length == 0) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_INVALID_WKB, "WKB payload is empty");
    }
    Cursor cursor = new Cursor(wkb);
    ReadContext context = new ReadContext(limits);
    Geometry geometry = readGeometry(cursor, context, 1);
    if (cursor.remaining() != 0) {
      throw invalid("Trailing bytes after WKB geometry", cursor.position);
    }
    GeometryLayoutSupport.validateRepresentable(geometry, limits);
    GeometrySridSupport.resolveConsistentSrid(geometry);
    return geometry;
  }

  private Geometry readGeometry(Cursor in, ReadContext context, int depth)
      throws GeometryCodecException {
    if (depth > limits.maxDepth()) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_LIMIT_EXCEEDED,
          "WKB nesting depth limit of " + limits.maxDepth() + " exceeded",
          in.position);
    }
    context.component();
    WkbHeader header = readHeader(in);
    Geometry geometry;
    switch (header.baseType) {
      case WKB_POINT -> geometry = readPoint(in, header, context);
      case WKB_LINESTRING -> geometry = readLineString(in, header, context);
      case WKB_POLYGON -> geometry = readPolygon(in, header, context);
      case WKB_MULTIPOINT -> geometry = readMultiPoint(in, header, context, depth);
      case WKB_MULTILINESTRING -> geometry = readMultiLineString(in, header, context, depth);
      case WKB_MULTIPOLYGON -> geometry = readMultiPolygon(in, header, context, depth);
      case WKB_GEOMETRYCOLLECTION ->
          geometry = readGeometryCollection(in, header, context, depth);
      case WKB_CIRCULARSTRING -> geometry = readCircularString(in, header, context);
      case WKB_COMPOUNDCURVE -> geometry = readCompoundCurve(in, header, context, depth);
      case WKB_CURVEPOLYGON -> geometry = readCurvePolygon(in, header, context, depth);
      case WKB_MULTICURVE -> geometry = readMultiCurve(in, header, context, depth);
      case WKB_MULTISURFACE -> geometry = readMultiSurface(in, header, context, depth);
      default -> throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY,
          "Unsupported WKB geometry type: " + header.baseType,
          in.position);
    }
    if (header.srid != null) {
      geometry.setSRID(header.srid);
    }
    return geometry;
  }

  private WkbHeader readHeader(Cursor in) throws GeometryCodecException {
    ByteOrder order = in.readOrder();
    int rawType = in.readInt(order);
    if ((rawType & EWKB_BBOX) != 0) {
      throw invalid("EWKB BBOX flag is not supported", in.position - Integer.BYTES);
    }
    int flags = rawType & (EWKB_Z | EWKB_M | EWKB_SRID);
    boolean hasZFlag = (rawType & EWKB_Z) != 0;
    boolean hasMFlag = (rawType & EWKB_M) != 0;

    int isoPart = rawType & EWKB_TYPE_MASK;
    int isoDimension = isoPart / 1000;
    int baseType = isoPart % 1000;
    if (isoDimension > 3) {
      throw invalid("Unsupported WKB dimension offset", in.position - Integer.BYTES);
    }
    if (isoDimension > 0 && flags != 0) {
      throw invalid(
          "Mixed ISO/SQL-MM dimension offset and EWKB flags", in.position - Integer.BYTES);
    }
    CoordinateLayout layout =
        isoDimension > 0
            ? switch (isoDimension) {
              case 1 -> CoordinateLayout.XYZ;
              case 2 -> CoordinateLayout.XYM;
              case 3 -> CoordinateLayout.XYZM;
              default -> throw invalid("Unsupported WKB dimension", in.position - Integer.BYTES);
            }
            : CoordinateLayout.of(hasZFlag, hasMFlag);

    if (baseType < WKB_POINT || baseType > WKB_MULTISURFACE) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_GEOMETRY,
          "Unsupported WKB geometry type: " + baseType,
          in.position - Integer.BYTES);
    }
    Integer srid = null;
    if ((rawType & EWKB_SRID) != 0) {
      int value = in.readInt(order);
      if (value < 0) {
        throw new GeometryCodecException(
            GeometryCodecErrorCode.GC_INVALID_SRID,
            "Negative SRID in WKB: " + value,
            in.position - Integer.BYTES);
      }
      srid = value;
    }
    return new WkbHeader(order, baseType, layout, srid, rawType);
  }

  private Point readPoint(Cursor in, WkbHeader header, ReadContext context)
      throws GeometryCodecException {
    context.coordinate(1);
    double[] values = readOrdinateValues(in, header.layout);
    double x = values[0];
    double y = values[1];
    double z = header.layout.hasZ() ? values[2] : Double.NaN;
    double m = header.layout.hasM() ? values[header.layout.mIndex()] : Double.NaN;
    if (Double.isNaN(x) && Double.isNaN(y)) {
      if (!Double.isNaN(z) || !Double.isNaN(m)) {
        throw invalid("Half-NaN empty POINT representation", in.position);
      }
      return new Point(emptySequence(header.layout), factory);
    }
    requireFiniteXy(x, y, in.position);
    requireNonInfinite(z, "Z", in.position);
    requireNonInfinite(m, "M", in.position);
    CoordinateSequence sequence = createSequence(header.layout, 1);
    setOrdinates(sequence, 0, header.layout, x, y, z, m);
    return factory.createPoint(sequence);
  }

  private LineString readLineString(Cursor in, WkbHeader header, ReadContext context)
      throws GeometryCodecException {
    long count = readCount(in, header.order, context, "coordinate");
    validatePayload(in, count, (long) header.layout.dimension() * Double.BYTES, "coordinate");
    if (count == 1) {
      throw invalid("Non-empty LINESTRING requires at least two coordinates", in.position);
    }
    return factory.createLineString(readSequence(in, header, count, context));
  }

  private Polygon readPolygon(Cursor in, WkbHeader header, ReadContext context)
      throws GeometryCodecException {
    long ringCount = readCount(in, header.order, context, "POLYGON ring");
    validatePayload(in, ringCount, 1, "POLYGON ring");
    if (ringCount == 0) {
      return new Polygon(new LinearRing(emptySequence(header.layout), factory), null, factory);
    }
    if (ringCount > Integer.MAX_VALUE) {
      throw limitExceeded("POLYGON ring count");
    }
    LinearRing shell = readRing(in, header, context);
    LinearRing[] holes = new LinearRing[(int) ringCount - 1];
    for (int i = 0; i < holes.length; i++) {
      holes[i] = readRing(in, header, context);
    }
    return factory.createPolygon(shell, holes);
  }

  private LinearRing readRing(Cursor in, WkbHeader header, ReadContext context)
      throws GeometryCodecException {
    long count = readCount(in, header.order, context, "coordinate");
    validatePayload(in, count, (long) header.layout.dimension() * Double.BYTES, "coordinate");
    if (count < 4) {
      throw invalid("Linear ring requires at least four coordinates", in.position);
    }
    CoordinateSequence sequence = readSequence(in, header, count, context);
    if (count > 0 && !isClosed(sequence)) {
      throw invalid("Linear ring is not closed", in.position);
    }
    return new LinearRing(sequence, factory);
  }

  private MultiPoint readMultiPoint(
      Cursor in, WkbHeader header, ReadContext context, int depth)
      throws GeometryCodecException {
    long count = readCount(in, header.order, context, "MULTIPOINT member");
    validatePayload(in, count, 5, "MULTIPOINT member");
    if (count == 0) {
      requireXyEmpty(header.layout, "MULTIPOINT");
      return factory.createMultiPoint(new Point[0]);
    }
    if (count > Integer.MAX_VALUE) {
      throw limitExceeded("MULTIPOINT member count");
    }
    List<Point> points = new ArrayList<>(Math.min((int) count, 1024));
    for (int i = 0; i < count; i++) {
      Geometry child = readGeometry(in, context, depth + 1);
      if (!(child instanceof Point point)) {
        throw invalid("MULTIPOINT member must be a POINT", in.position);
      }
      points.add(point);
    }
    return factory.createMultiPoint(points.toArray(Point[]::new));
  }

  private MultiLineString readMultiLineString(
      Cursor in, WkbHeader header, ReadContext context, int depth)
      throws GeometryCodecException {
    long count = readCount(in, header.order, context, "MULTILINESTRING member");
    validatePayload(in, count, 5, "MULTILINESTRING member");
    if (count == 0) {
      requireXyEmpty(header.layout, "MULTILINESTRING");
      return factory.createMultiLineString(new LineString[0]);
    }
    if (count > Integer.MAX_VALUE) {
      throw limitExceeded("MULTILINESTRING member count");
    }
    List<LineString> lines = new ArrayList<>(Math.min((int) count, 1024));
    for (int i = 0; i < count; i++) {
      Geometry child = readGeometry(in, context, depth + 1);
      if (!(child instanceof LineString line)) {
        throw invalid("MULTILINESTRING member must be a LINESTRING", in.position);
      }
      lines.add(line);
    }
    return factory.createMultiLineString(lines.toArray(LineString[]::new));
  }

  private MultiPolygon readMultiPolygon(
      Cursor in, WkbHeader header, ReadContext context, int depth)
      throws GeometryCodecException {
    long count = readCount(in, header.order, context, "MULTIPOLYGON member");
    validatePayload(in, count, 5, "MULTIPOLYGON member");
    if (count == 0) {
      requireXyEmpty(header.layout, "MULTIPOLYGON");
      return factory.createMultiPolygon(new Polygon[0]);
    }
    if (count > Integer.MAX_VALUE) {
      throw limitExceeded("MULTIPOLYGON member count");
    }
    List<Polygon> polygons = new ArrayList<>(Math.min((int) count, 1024));
    for (int i = 0; i < count; i++) {
      Geometry child = readGeometry(in, context, depth + 1);
      if (!(child instanceof Polygon polygon)) {
        throw invalid("MULTIPOLYGON member must be a POLYGON", in.position);
      }
      polygons.add(polygon);
    }
    return factory.createMultiPolygon(polygons.toArray(Polygon[]::new));
  }

  private GeometryCollection readGeometryCollection(
      Cursor in, WkbHeader header, ReadContext context, int depth)
      throws GeometryCodecException {
    long count = readCount(in, header.order, context, "GEOMETRYCOLLECTION member");
    validatePayload(in, count, 5, "GEOMETRYCOLLECTION member");
    if (count == 0) {
      requireXyEmpty(header.layout, "GEOMETRYCOLLECTION");
      return factory.createGeometryCollection(new Geometry[0]);
    }
    if (count > Integer.MAX_VALUE) {
      throw limitExceeded("GEOMETRYCOLLECTION member count");
    }
    List<Geometry> geometries = new ArrayList<>(Math.min((int) count, 1024));
    for (int i = 0; i < count; i++) {
      geometries.add(readGeometry(in, context, depth + 1));
    }
    return factory.createGeometryCollection(geometries.toArray(Geometry[]::new));
  }

  private CircularString readCircularString(
      Cursor in, WkbHeader header, ReadContext context)
      throws GeometryCodecException {
    long count = readCount(in, header.order, context, "coordinate");
    validatePayload(in, count, (long) header.layout.dimension() * Double.BYTES, "coordinate");
    if (count == 0) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE,
          "Empty CIRCULARSTRING is not representable");
    }
    if (count < 3 || count % 2 == 0) {
      throw invalid("CIRCULARSTRING requires an odd number of at least three controls", in.position);
    }
    CoordinateSequence sequence = readSequence(in, header, count, context);
    consumeCurveMaterialization(context, sequence);
    return new CircularString(sequence, factory);
  }

  private CompoundCurve readCompoundCurve(
      Cursor in, WkbHeader header, ReadContext context, int depth)
      throws GeometryCodecException {
    long count = readCount(in, header.order, context, "COMPOUNDCURVE component");
    validatePayload(in, count, 5, "COMPOUNDCURVE component");
    if (count == 0) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE,
          "Empty COMPOUNDCURVE is not representable");
    }
    if (count > Integer.MAX_VALUE) {
      throw limitExceeded("COMPOUNDCURVE component count");
    }
    List<LineString> components = new ArrayList<>(Math.min((int) count, 1024));
    Coordinate previousEnd = null;
    for (int i = 0; i < count; i++) {
      Geometry child = readGeometry(in, context, depth + 1);
      if (!(child instanceof LineString line)) {
        throw invalid("COMPOUNDCURVE component must be a line/curve", in.position);
      }
      requireLayout(header.layout, GeometryLayoutSupport.primitiveLayout(line), "COMPOUNDCURVE");
      Coordinate first = firstPoint(line);
      if (previousEnd != null && !same2D(previousEnd, first)) {
        throw invalid("COMPOUNDCURVE components are not connected", in.position);
      }
      previousEnd = lastPoint(line);
      components.add(line);
    }
    consumeCompoundMaterialization(context, components);
    return new CompoundCurve(components, factory);
  }

  private CurvePolygon readCurvePolygon(
      Cursor in, WkbHeader header, ReadContext context, int depth)
      throws GeometryCodecException {
    long count = readCount(in, header.order, context, "CURVEPOLYGON ring");
    validatePayload(in, count, 5, "CURVEPOLYGON ring");
    if (count == 0) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_EMPTY_CURVE,
          "Empty CURVEPOLYGON is not representable");
    }
    if (count > Integer.MAX_VALUE) {
      throw limitExceeded("CURVEPOLYGON ring count");
    }
    List<LineString> rings = new ArrayList<>(Math.min((int) count, 1024));
    for (int i = 0; i < count; i++) {
      Geometry child = readGeometry(in, context, depth + 1);
      if (!(child instanceof LineString ring)) {
        throw invalid("CURVEPOLYGON ring must be a line/curve", in.position);
      }
      requireLayout(header.layout, GeometryLayoutSupport.primitiveLayout(ring), "CURVEPOLYGON");
      if (!isClosed(ring)) {
        throw invalid("CURVEPOLYGON ring is not closed", in.position);
      }
      rings.add(ring);
    }
    consumeCurvePolygonMaterialization(context, rings);
    return new CurvePolygon(rings, factory);
  }

  private MultiCurve readMultiCurve(
      Cursor in, WkbHeader header, ReadContext context, int depth)
      throws GeometryCodecException {
    long count = readCount(in, header.order, context, "MULTICURVE component");
    validatePayload(in, count, 5, "MULTICURVE component");
    if (count == 0) {
      requireXyEmpty(header.layout, "MULTICURVE");
      return new MultiCurve(List.of(), factory);
    }
    if (count > Integer.MAX_VALUE) {
      throw limitExceeded("MULTICURVE component count");
    }
    List<LineString> curves = new ArrayList<>(Math.min((int) count, 1024));
    for (int i = 0; i < count; i++) {
      Geometry child = readGeometry(in, context, depth + 1);
      if (!(child instanceof LineString curve)) {
        throw invalid("MULTICURVE component must be a line/curve", in.position);
      }
      curves.add(curve);
    }
    return new MultiCurve(curves, factory);
  }

  private MultiSurface readMultiSurface(
      Cursor in, WkbHeader header, ReadContext context, int depth)
      throws GeometryCodecException {
    long count = readCount(in, header.order, context, "MULTISURFACE component");
    validatePayload(in, count, 5, "MULTISURFACE component");
    if (count == 0) {
      requireXyEmpty(header.layout, "MULTISURFACE");
      return new MultiSurface(List.of(), factory);
    }
    if (count > Integer.MAX_VALUE) {
      throw limitExceeded("MULTISURFACE component count");
    }
    List<Polygon> surfaces = new ArrayList<>(Math.min((int) count, 1024));
    for (int i = 0; i < count; i++) {
      Geometry child = readGeometry(in, context, depth + 1);
      if (!(child instanceof Polygon surface)) {
        throw invalid("MULTISURFACE component must be a polygon/surface", in.position);
      }
      surfaces.add(surface);
    }
    return new MultiSurface(surfaces, factory);
  }

  private CoordinateSequence readSequence(
      Cursor in, WkbHeader header, long count, ReadContext context)
      throws GeometryCodecException {
    if (count > Integer.MAX_VALUE) {
      throw limitExceeded("coordinate count");
    }
    CoordinateSequence sequence = createSequence(header.layout, (int) count);
    context.coordinate(count);
    for (int i = 0; i < count; i++) {
      double[] values = readOrdinateValues(in, header.layout);
      double x = values[0];
      double y = values[1];
      double z = header.layout.hasZ() ? values[2] : Double.NaN;
      double m = header.layout.hasM() ? values[header.layout.mIndex()] : Double.NaN;
      requireFiniteXy(x, y, in.position);
      requireNonInfinite(z, "Z", in.position);
      requireNonInfinite(m, "M", in.position);
      setOrdinates(sequence, i, header.layout, x, y, z, m);
    }
    return sequence;
  }

  private double[] readOrdinateValues(Cursor in, CoordinateLayout layout)
      throws GeometryCodecException {
    double[] values = new double[layout.dimension()];
    values[0] = in.readDouble(currentOrder(in));
    values[1] = in.readDouble(currentOrder(in));
    if (layout.hasZ()) {
      values[2] = in.readDouble(currentOrder(in));
    }
    if (layout.hasM()) {
      values[layout.mIndex()] = in.readDouble(currentOrder(in));
    }
    return values;
  }

  private ByteOrder currentOrder(Cursor in) {
    return in.order;
  }

  private long readCount(
      Cursor in, ByteOrder order, ReadContext context, String role)
      throws GeometryCodecException {
    int signed = in.readInt(order);
    if (signed < 0) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_LIMIT_EXCEEDED,
          "Negative " + role + " count in WKB: " + signed,
          in.position - Integer.BYTES);
    }
    long count = signed;
    if ((role.contains("component") || role.contains("ring") || role.contains("member"))
        && count > limits.maxComponents()) {
      throw limitExceeded(role + " count");
    }
    if (role.equals("coordinate") && count > limits.maxCoordinates()) {
      throw limitExceeded(role + " count");
    }
    return count;
  }

  private void validatePayload(Cursor in, long count, long bytesPerItem, String role)
      throws GeometryCodecException {
    if (bytesPerItem <= 0) {
      return;
    }
    long available = in.remaining();
    if (count > available / bytesPerItem) {
      throw invalid("Truncated WKB payload for " + role, in.position);
    }
  }

  private CoordinateSequence createSequence(CoordinateLayout layout, int count) {
    return factory
        .getCoordinateSequenceFactory()
        .create(count, layout.dimension(), layout.measures());
  }

  private CoordinateSequence emptySequence(CoordinateLayout layout) {
    return createSequence(layout, 0);
  }

  private static void setOrdinates(
      CoordinateSequence sequence,
      int index,
      CoordinateLayout layout,
      double x,
      double y,
      double z,
      double m) {
    sequence.setOrdinate(index, CoordinateSequence.X, x);
    sequence.setOrdinate(index, CoordinateSequence.Y, y);
    if (layout.hasZ()) {
      sequence.setOrdinate(index, layout.zIndex(), z);
    }
    if (layout.hasM()) {
      sequence.setOrdinate(index, layout.mIndex(), m);
    }
  }

  private void requireLayout(CoordinateLayout expected, CoordinateLayout actual, String role)
      throws GeometryCodecException {
    if (expected != actual) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT,
          "Inconsistent layout in " + role + ": header " + expected + ", member " + actual);
    }
  }

  private void requireXyEmpty(CoordinateLayout layout, String role)
      throws GeometryCodecException {
    if (layout != CoordinateLayout.XY) {
      throw new GeometryCodecException(
          GeometryCodecErrorCode.GC_UNSUPPORTED_LAYOUT,
          "Empty " + role + " cannot retain layout " + layout);
    }
  }

  private void consumeCurveMaterialization(ReadContext context, CoordinateSequence controls)
      throws GeometryCodecException {
    long remaining = context.remainingCurveVertices();
    long total = 1;
    for (int i = 0; i + 2 < controls.size(); i += 2) {
      long available = remaining - (total - 1);
      if (available < 0) {
        throw limitExceeded("curve materialization");
      }
      long count;
      try {
        count =
            new ArcSegment(
                    controls.getCoordinate(i),
                    controls.getCoordinate(i + 1),
                    controls.getCoordinate(i + 2))
                .linearizedCoordinateCount(CircularString.DEFAULT_MAX_ERROR, available);
      } catch (IllegalArgumentException e) {
        throw limitExceeded("curve materialization");
      }
      if (count > available) {
        throw limitExceeded("curve materialization");
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
      throw limitExceeded("curve materialization");
    }
  }

  private static GeometryCodecException limitExceeded(String role) {
    return new GeometryCodecException(
        GeometryCodecErrorCode.GC_LIMIT_EXCEEDED, role + " exceeds configured limit");
  }

  private static void requireFiniteXy(double x, double y, int offset)
      throws GeometryCodecException {
    if (!Double.isFinite(x) || !Double.isFinite(y)) {
      throw invalid("X and Y ordinates must be finite", offset);
    }
  }

  private static void requireNonInfinite(double value, String role, int offset)
      throws GeometryCodecException {
    if (Double.isInfinite(value)) {
      throw invalid(role + " ordinate must not be infinite", offset);
    }
  }

  private static GeometryCodecException invalid(String message, int offset) {
    return new GeometryCodecException(GeometryCodecErrorCode.GC_INVALID_WKB, message, offset);
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
    return same2D(firstPoint(line), lastPoint(line));
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

  private record WkbHeader(
      ByteOrder order, int baseType, CoordinateLayout layout, Integer srid, int rawType) {}

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
        throw limitExceeded("Geometry component count");
      }
    }

    private void coordinate(long count) throws GeometryCodecException {
      try {
        coordinates = Math.addExact(coordinates, count);
      } catch (ArithmeticException e) {
        throw limitExceeded("Coordinate count");
      }
      if (coordinates > limits.maxCoordinates()) {
        throw limitExceeded("Coordinate count");
      }
    }

    private long remainingCurveVertices() {
      return Math.max(0, limits.maxMaterializedCurveVertices() - curveVertices);
    }

    private void curveVertices(long count) throws GeometryCodecException {
      try {
        curveVertices = Math.addExact(curveVertices, count);
      } catch (ArithmeticException e) {
        throw limitExceeded("Curve materialization");
      }
      if (curveVertices > limits.maxMaterializedCurveVertices()) {
        throw limitExceeded("Curve materialization");
      }
    }
  }

  private static final class Cursor {
    private final byte[] data;
    private int position;
    private ByteOrder order;

    private Cursor(byte[] data) {
      this.data = data;
    }

    private int remaining() {
      return data.length - position;
    }

    private ByteOrder readOrder() throws GeometryCodecException {
      ensure(1);
      int marker = data[position++] & 0xff;
      order =
          switch (marker) {
            case 0 -> ByteOrder.BIG_ENDIAN;
            case 1 -> ByteOrder.LITTLE_ENDIAN;
            default -> throw invalid("Invalid WKB byte order: " + marker, position - 1);
          };
      return order;
    }

    private int readInt(ByteOrder byteOrder) throws GeometryCodecException {
      ensure(Integer.BYTES);
      int value =
          ((data[position] & 0xff) << 24)
              | ((data[position + 1] & 0xff) << 16)
              | ((data[position + 2] & 0xff) << 8)
              | (data[position + 3] & 0xff);
      if (byteOrder == ByteOrder.LITTLE_ENDIAN) {
        value =
            ((data[position + 3] & 0xff) << 24)
                | ((data[position + 2] & 0xff) << 16)
                | ((data[position + 1] & 0xff) << 8)
                | (data[position] & 0xff);
      }
      position += Integer.BYTES;
      return value;
    }

    private double readDouble(ByteOrder byteOrder) throws GeometryCodecException {
      long bits = 0;
      ensure(Double.BYTES);
      if (byteOrder == ByteOrder.BIG_ENDIAN) {
        for (int i = 0; i < Double.BYTES; i++) {
          bits = (bits << 8) | (data[position + i] & 0xff);
        }
      } else {
        for (int i = Double.BYTES - 1; i >= 0; i--) {
          bits = (bits << 8) | (data[position + i] & 0xff);
        }
      }
      position += Double.BYTES;
      return Double.longBitsToDouble(bits);
    }

    private void ensure(int required) throws GeometryCodecException {
      if (required > remaining()) {
        throw invalid("Unexpected end of WKB", position);
      }
    }
  }
}
