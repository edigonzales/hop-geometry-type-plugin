# GeoJSON geometry interchange

Geometry Type owns the geometry codec used by the Geoprocessing converter and the
Vector/Raster feature and file transforms. It reads and writes **Geometry
objects**. A Feature, FeatureCollection, property mapping or file stream belongs
to the Vector/Raster plugin; the shared codec rejects those document types with
an actionable error.

The existing Hop Geometry string and binary representations remain EWKT/EWKB.
GeoJSON is an explicit conversion and is never guessed by `readText`.

## Profiles

| Profile | Coordinates and purpose |
| --- | --- |
| `RFC7946` | Standard GeoJSON: WGS84 longitude/latitude; optional WGS84 ellipsoidal height in metres |
| `LEGACY2008` | GeoJSON 1.0 from 2008, including named `crs`; coordinates remain X/Y |
| `JSONFG10` | OGC JSON-FG 1.0.0: declared CRS axes, supported circular arcs and measures |
| `AUTO` | Read only: recognize JSON-FG markers, then legacy `crs`, then inherited context; unmarked input is RFC 7946 |

Normative references: [RFC 7946](https://www.rfc-editor.org/rfc/rfc7946.html),
[GeoJSON 2008](https://geojson.org/geojson-spec.html), and
[OGC JSON-FG 1.0.0](https://docs.ogc.org/is/21-045r1/21-045r1.html).

The codec performs **no reprojection, unit conversion or height transformation**.
Use Geometry Reproject explicitly for a horizontal CRS conversion. Its preservation
of Z does not establish the height datum required by a three-dimensional output
format.

## Java API

```java
GeometryCodec codec = new GeometryCodec(GeometryReadLimits.defaults());
GeoJsonReadResult result = codec.readGeoJson(
    "{\"type\":\"Point\",\"coordinates\":[7.4386,46.9511]}",
    GeoJsonReadOptions.defaults());
Geometry geometry = result.geometry();  // native X/Y, SRID 4326
String contextJson = result.context().toJson();
String output = codec.writeGeoJson(geometry, GeoJsonWriteOptions.defaults());
```

`readGeoJsonObject` and `writeGeoJsonObject` provide the same conversion for
already parsed `Map<String,Object>` values. Their public APIs do not expose
GeoTools or Jackson types. `GeoJsonReadResult` carries a separate versioned
`GeoJsonContext`; the codec never stores interchange metadata in `Geometry.userData`.

The context contains the profile, full `coordRefSys` descriptor and measure
metadata. Serialize it with `toJson` and restore it with `fromJson`. Keep it
alongside the native Geometry when a numeric SRID cannot express a compound
horizontal/vertical CRS or a measure's unit and description. A context that
conflicts with the Geometry SRID fails conversion.

### Legacy LV95

```java
Geometry lv95 = codec.readText("SRID=2056;POINT (2600000 1200000)");
GeoJsonWriteOptions legacy = new GeoJsonWriteOptions(
    GeoJsonProfile.LEGACY2008, GeoJsonContext.empty(), false, false, false);
String json = codec.writeGeoJson(lv95, legacy);
// coordinates: [2600000.0,1200000.0], named CRS EPSG:2056
```

### JSON-FG measures and authority axes

```java
Geometry point = codec.readText("SRID=4326;POINT M (7 47 12.5)");
GeoJsonContext context = new GeoJsonContext(
    GeoJsonProfile.JSONFG10,
    "http://www.opengis.net/def/crs/EPSG/0/4326",
    Map.of("enabled", true, "unit", "s", "description", "Elapsed time"));
String json = codec.writeGeoJson(point,
    new GeoJsonWriteOptions(GeoJsonProfile.JSONFG10, context, false, false, false));
// JSON-FG EPSG:4326 coordinates are [47.0,7.0,12.5].
// Native Geometry remains X=7, Y=47, M=12.5.
```

JSON-FG roots include the applicable `conformsTo` declarations. Feature encoders
lift document metadata from the Geometry object to the enclosing document and
place measure metadata at the permitted Feature/collection scope. A standalone
JSON-FG Geometry input must declare core conformance; embedded objects inherit
validated document context.

## CRS and dimensions

Supported references are positive EPSG codes expressed as `EPSG:<code>`, OGC
EPSG URNs or definition URLs, plus CRS84/CRS84h references. JSON-FG `Reference`
objects without an epoch are accepted. An explicit two-element horizontal and
vertical CRS array preserves its full descriptor in the context; the Geometry
carries the horizontal EPSG SRID. No CRS document is fetched over HTTP.

Geographic and projected 2D/3D CRS are supported. Geocentric, engineering, arbitrary
WKT, dynamic epochs and standalone vertical CRS are rejected. JSON-FG follows the
CRS authority axis order, while native Geometry always uses X/Y. Thus EPSG:4326
JSON-FG is latitude/longitude; RFC 7946 and CRS84 are longitude/latitude. EPSG:3035
northing/easting is also mapped to native easting/northing.

RFC input with three ordinates produces an XYZ Geometry with SRID 4979 and a
CRS84h context. Writing Z requires a 3D WGS84 CRS or an explicit CRS84h context;
SRID 4326 alone does not assert that an arbitrary Z value is an ellipsoidal height.
XYM is never mistaken for XYZ. JSON-FG supports XY, XYZ, XYM and XYZM with a
consistent CRS dimension and `measures.enabled` declaration.

## Geometry and loss policies

Standard Point, LineString, Polygon, MultiPoint, MultiLineString, MultiPolygon and
GeometryCollection are supported. JSON-FG additionally supports CircularString,
CompoundCurve, CurvePolygon, MultiCurve and MultiSurface using exact control
points and the existing native curve classes.

JSON-FG's representability rules are checked explicitly: CircularStrings contain
3, 5, 7, 9 or 11 distinct non-collinear arc control points, compound parts join,
rings close, and GeometryCollections contain only non-nested Simple Feature
geometries. Unsupported solids and unknown custom types are errors. Longer
CircularStrings are not split silently. Embedded JSON-FG geometries inherit CRS
and measure metadata.

Unknown Geometry-level foreign members are rejected rather than discarded; `bbox`
is explicitly omitted. Feature-level foreign metadata is handled by the separate
Feature codec. JSON-FG Simple Feature geometries must also pass topology validity
checks; true curves use their exact structural arc, join and closure checks rather
than treating their materialized chords as authoritative curves.

Legacy XYZ values remain XYZ with their named horizontal CRS; this profile does
not establish or transform a vertical datum. RFC and JSON-FG retain the stricter
3D CRS requirements described above. Declared NaN Z/M ordinates are never silently
normalized away, including geometries created through ordinary JTS factories.

There is no implicit curve linearization, geometry repair, densification, number
rounding or antimeridian cutting. RFC polygon ring orientation is normalized on
a copy. Coordinates must be finite; RFC longitude/latitude must be within the
supported geographic range. The input Geometry, its SRIDs and coordinate sequences
are never modified.

All loss options default to false:

- `dropZ`: explicitly remove Z, choosing an appropriate 2D CRS context where needed.
- `dropM`: explicitly remove measures, including when writing RFC/Legacy output.
- `emptyToNull`: explicitly serialize an otherwise non-representable EMPTY as JSON null.

NULL remains NULL. An empty GeometryCollection remains a GeometryCollection with
an empty `geometries` array. EMPTY members cannot be silently removed from a
partially populated collection or replaced by invalid embedded null values.

## JSON payloads, limits and failures

`JsonSupport` is the shared strict JSON utility. It uses Java maps, lists, strings,
booleans, null, `BigInteger` and `BigDecimal`. Properties and IDs do not pass through
floating point when parsed or serialized. Native JTS coordinate ordinates remain
double precision. Whitespace and object member ordering are not a preservation
contract.

`JsonSupport.open(Reader)` supplies a pull reader with parser-neutral tokens,
`readValue` and `skipValue`. It supports one-feature-at-a-time file processing
without materializing a FeatureCollection. Closing it closes the supplied reader.
Each materialized value is limited to 64 MiB of characters, strings to 16 MiB and
JSON nesting to 128 levels. Streaming documents can be larger. Duplicate keys,
trailing roots, comments, malformed structures and non-finite JSON numbers are
rejected. Existing Geometry limits additionally constrain geometry depth,
components, coordinates and curve materialization before Geometry allocation.

Failures use checked `GeometryCodecException` codes such as `GC_INVALID_JSON`,
`GC_UNSUPPORTED_CRS`, `GC_LOSSY_CONVERSION`, `GC_NONFINITE_COORDINATE` and
`GC_LIMIT_EXCEEDED`. Missing shared runtime libraries are integration failures;
they must not be converted into per-row NULL values.

## Verification

Java tests cover independent coordinate/axis cases, exact curve and Z/M
preservation, strict policies and a cold isolated plugin classloader with hidden
Indriya services. Maven tests emit representative JSON-FG documents. The canonical
Python tests validate these actual outputs against the vendored official OGC
1.0 schemas with offline reference resolution. The geometry codec adds no
additional runtime JAR owner: Jackson 3, GeoTools and Indriya remain in the
Geometry Type ZIP's shared `sogeo-geometry` runtime.
