# Implementierungsprotokoll – Geometry Converter, Etappe 1 (Paket B)

**Status:** abgeschlossen am 2026-10-17
**Auftrag:** Spezifikation `hop-geometry-converter-spec-v2`, Revision 2, Kapitel 05 A0–A2 und Paket B.
**Scope:** gemeinsame Codec-API und Hop-Feldtypkonvertierung im Geometry-Type-Repository.
**Nicht in dieser Etappe:** `GeometryConverterMeta`, Transform-Runtime, SWT-Dialog, Geoprocessing-/Calculator-Änderungen, neue Plugins oder Installationspakete.

## 1. Ausgangslage

| Punkt | Wert |
|---|---|
| Branch | `main` |
| HEAD vor Änderungen | `94bc4d90b2c51f1fc85c9498b2993707ccb1fb14` |
| Lokale Änderungen vor Start | nur untracked `hop-geometry-converter-spec-v2/` |
| Java-Baseline | Temurin 21.0.10+7 |
| Maven | 3.9.16 |
| Hop | 2.19.0 |
| JTS | 1.20.0 |
| Projektversion | `0.2.0-SNAPSHOT` |
| CI-Helper-Pin | `16f5518dc21ad5053ce4f137c6bdf97a5ebce5b7` |
| Spezifikation | Revision 2, README + Kapitel 01–08 |
| OST-Archiv | `/Users/stefan/Downloads/apache-hop-plugins-geometry-fields-converter-main.zip` |
| OST-SHA-256 | `38f9e636d17fd7afa6750ca074675f749946ea421b72a58af9a564efda3d956c` |
| OST-ZIP-Kommentar | `e9952934aedeb92690f4cec4d387caf1a4988370` |

Das OST-Archiv wurde per SHA-256 und ZIP-Kommentar geprüft und gelesen; insbesondere
`CurveWktReader.java`, die WKT-/WKB-Dispatcher und die OST-Tests. Es wurde **kein**
OST-Maven-Build ausgeführt und **kein OST-Code unverändert übernommen**. Die
Implementierung folgt dem Funktionsvertrag; die OST-Strukturen dienten nur als
Verhaltens-/Fehlerreferenz für die OR-Fälle.

Der lokale `hop-plugin-ci`-Checkout liegt auf einem neueren Commit als der
Workflow-Pin. Für die lokalen Builds wurde der gepinnte Stand per `git archive`
nach `/tmp/hop-ci-16f5518` entpackt; das Helper-Repository wurde nicht verändert.

## 2. Baseline und Bestandsschutz

Vor Quelländerungen:

```bash
mvn -s /tmp/hop-ci-settings.xml -B -ntp test
```

Ergebnis: **BUILD SUCCESS**, 34 Tests, 0 Failures, 0 Errors, 1 übersprungen
(`PostgisDatabaseIntegrationTest` ohne `POSTGIS_TEST_ENABLED`).

Die bisherigen Ausgaben wurden aus dem unveränderten Baseline-Build erzeugt und in
[`legacy-baseline.md`](legacy-baseline.md) eingefroren. `LegacyGeometryContractTest`
prüft seither exakt:

- `getString` für POINT, POINT Z und EWKT mit SRID 2056;
- CurvePolygon-WKT und SRID-Präfix;
- `CurveGeometrySupport.writeWkb`-Hex für lineare Geometrie und Kurven;
- `ValueMetaGeometry.writeData` inklusive NULL-Flag/Längen-Rahmung;
- Legacy-`EMPTY → NULL` und EWKT-SRID;
- Typ-ID `43663879` und Classloader-Gruppe `sogeo-geometry`.

## 3. Umgesetzte Kapitel-02-Bausteine

### §3 Werttypen, Optionen und Limits

Neu unter `hop-geometry-type/src/main/java/com/atolcd/hop/gis/geometry/codec/`:

- `CoordinateLayout` (XY/XYZ/XYM/XYZM, Dimension, Measures, Z/M-Indizes, ISO-Offset, EWKB-Flags)
- `PointCoordinates`
- `WkbDialect`, `GeometryByteOrder`, `SridEncoding`
- `WkbWriteOptions`, `WktWriteOptions`
- `GeometryReadLimits` (Default 64 / 10 Mio. / 1 Mio. / 5 Mio.)
- `GeometryCodecErrorCode`, `GeometryCodecException`

`WkbWriteOptions.standardFieldBinary()` ist EWKB/Big Endian/`IF_KNOWN`;
`iso(order)` ist ISO ohne SRID; `ewkb(order)` ist EWKB mit `ALWAYS`.

### §4 Fassade `GeometryCodec`

Implementiert: `readText`, `readBinary`, `readHex`, `writeText`, `writeBinary`,
`writeHex`, `copyExact`, `validateSupportedGeometry`, `limits()`.
NULL/blank-Verhalten, vollständiger Textverbrauch, keine Hex→WKT-Fallbacks und
keine Mutation sind getestet.

### §5 Fehlerbehandlung

Alle 13 `GC_*`-Codes sind vorhanden. Meldungen sind kurz und stabil, Offsets
werden für Text (Zeichen) und Binär (Bytes) geführt. Unbekannte Laufzeitfehler
werden nicht pauschal als Datenfehler maskiert.

### §6 WKT/EWKT und `CurveWktReader`

Neu:

- `curve/CurveWktReader` mit eigenem `WktTokenCursor`
- `curve/WktTokenCursor`

Unterstützt werden:

- Standardtypen und Kurventypen 8–12, auch in Collections;
- `Z`, `M`, `ZM`, `EMPTY`, Gross-/Kleinschreibung, äusserer Whitespace;
- Zahlengrammatik mit führendem Vorzeichen, Dezimalpunkt, Exponent, `.5`, `1.`;
- `NaN` nur an Z/M-Positionen; kein Kommentar-, Hex-Float- oder Suffix-Token;
- strikte Klammer-/Komma-/EOF-Prüfung; keine stillen unbekannten Mitglieder;
- Layouts aus Markern bzw. unmarkierter Ordinatenanzahl (2 → XY, 3 → XYZ);
- exakte Kurvenkontrolle, Ring-/Verbindungsprüfungen;
- Tiefen-, Koordinaten-, Komponenten- und Kurvenmaterialisierungsbudget.

`GeometryTextCodec` trennt das EWKT-Präfix strikt und schreibt WKT ohne Präfix
bzw. EWKT immer mit `SRID=n;`, auch 0.

`CurveWktWriter` wurde layouterhaltend für lineare Typen, Kurven, Multi- und
Collection-Typen erweitert. Heterogene `MULTICURVE`/`MULTISURFACE` werden mit
typisierten Mitgliedern geschrieben; heterogene Standard-Multi-Layouts werden
für WKT bewusst nicht still vereinheitlicht.

### §7 WKB/EWKB

`CurveWkbReader` und `CurveWkbWriter` wurden erweitert:

- Typen 1–12, rekursiv, inklusive Kurven in Collections;
- ISO/SQL-MM-Offsets und EWKB-Flags getrennt, Mischformen abgelehnt;
- BBOX-/unbekannte Flags, unbekannte Typen, negative/überlaufende Zähler,
  Restbytes und Trunkierungen als benannte Fehler;
- beide Byteordnungen, gemischte Kind-Byteordnungen lesbar;
- deklarierte Layouts, exakte Kurvenkontrollpunkte, leere Point-/Line-/Polygon-
  Repräsentationen;
- Depth-Limit und Kurvenmaterialisierungsbudget vor grossen Allokationen;
- keine Hop-Rahmung in der Nutzlast.

`CurveWkbReader.read(byte[])` bleibt als quellkompatible unchecked Hülle
(`IllegalArgumentException`) erhalten; die gemeinsame API nutzt
`readChecked(byte[])`. `CurveWkbWriter.write(Geometry)` behält den bisherigen
Default (Little Endian, EWKB bei bekannter SRID); der Optionspfad ist additiv.

### §8 Hex

`GeometryHexCodec`:

- Gross-/Kleinschreibung;
- genau ein optionales Präfix `0x`, `0X`, `\x`, `\X`;
- gerade Anzahl ASCII-Hexziffern, keine internen Leerzeichen, Unicode-Ziffern
  abgelehnt;
- Ausgabe uppercase ohne Präfix;
- `hasExplicitPrefix`/`looksLikeHeaderedHex` für die Select-Values-Erkennung.

### §9 Layout, Kurvenkopie und Budgets

- `GeometryLayoutSupport` liest deklarierte Sequenzlayouts für Punkt, Linie,
  Polygon, Kurven, Multi- und Collection-Typen; rekursive Validierung von
  endlichem XY, Z/M-NaN/Infinity, Limits und Typen.
- `CircularString` hat einen additiven `CoordinateSequence`-Konstruktor,
  `getControlPointSequence()` und ein explizites Layout; alte Array-Semantik
  bleibt erhalten. `copyInternal`/`reverseInternal` erhalten das Layout.
- `ArcSegment.linearizedCoordinateCount(maxError, maximum)` verwendet dieselbe
  Plan-/Sweep-/Segmentlogik wie `linearize()`, ohne Vertexliste und mit frühem
  Abbruch.
- Der Legacy-Array-Konstruktor von `CircularString` behält seine bisherige
  Z-Erkennung; neue Parser verwenden den Sequenzpfad.

### §10 SRID

`GeometrySridSupport` implementiert:

- `resolveConsistentSrid` ohne Mutation, inkl. Wurzel-/Kindregeln;
- `GC_MIXED_SRID` bei widersprüchlichen positiven SRIDs;
- `GC_INVALID_SRID` bei negativen Werten;
- `copyWithSrid` auf einer exakten Kopie, rekursiv, inkl. Kurven-/Polygon-
  Komponenten.

### §11 Punktkoordinaten

`PointCoordinateSupport`:

- `create` mit explizitem Layout, korrekter XYM/XYZM-Ordinatenposition;
- vollständig fehlendes Tupel → NULL;
- partielles NULL → `GC_PARTIAL_COORDINATE_NULL`;
- nicht endliche X/Y, Infinity in Z/M → `GC_NONFINITE_COORDINATE`;
- Z/M NULL oder NaN bleibt als NaN bei deklariertem Layout;
- `extract` nur für Point, EMPTY-Point → NULL-Ordinaten, Nicht-Points →
  `GC_NOT_A_POINT`.

### §12 Hop-Feldtypintegration

`ValueMetaGeometry`:

- `convertData` um Binary (WKB/EWKB) und die definierte Hexerkennung erweitert;
- String-Pfad nutzt weiterhin den Legacy-Wrapper `GeometryUtils`, inklusive
  `EMPTY → NULL`;
- `TYPE_GEOMETRY` bleibt Pass-through;
- neues `getBinary(Object)` als Quellmetadaten-Hook für den normalen
  `ValueMetaBinary`-Zieltyp; Standardoptionen EWKB/BE/IF_KNOWN, keine
  `writeData()`-Rahmung;
- `STORAGE_TYPE_NORMAL` und `INDEXED` werden normalisiert;
  `STORAGE_TYPE_BINARY_STRING` wird für Geometry-Binärausgabe klar abgelehnt;
- `getGeometry` normalisiert INDEXED-Geometry.

`GeometryUtils.getGeometryFromEWKT` nutzt den gemeinsamen Parser, behält aber
ausdrücklich die Legacy-EMPTY→NULL-Normalisierung. `CurveGeometrySupport`
erkennt kurvenhaltige Collections rekursiv, kopiert exakt und nutzt den neuen
WKB-Reader. `LinearGeometryCodec` behält seine bestehenden Methoden und erhielt
additive Options-/Limit-Overloads. `PostgisGeometrySupport.write` schreibt
layoutbewusstes EWKT über den gemeinsamen Codec; die bestehenden PostGIS-Tests
bleiben grün.

## 4. Tatsächlich ausgeführte Tests

### Baseline (JDK 21, ohne PostGIS)

```bash
mvn -s /tmp/hop-ci-settings.xml -B -ntp test
```

`BUILD SUCCESS`, 34 Tests, 0 Failures, 0 Errors, 1 skipped.

### Kanonischer Build (JDK 21, PostGIS 17-3.5)

Wegwerf-Container:

```bash
docker pull --platform linux/amd64 postgis/postgis:17-3.5
docker run --rm -d --platform linux/amd64 --name hop-geometry-type-postgis \
  -e POSTGRES_USER=hop -e POSTGRES_PASSWORD=hop -e POSTGRES_DB=hop \
  -p 5432:5432 postgis/postgis:17-3.5
export POSTGIS_TEST_ENABLED=true
export POSTGIS_TEST_HOST=localhost POSTGIS_TEST_PORT=5432
export POSTGIS_TEST_DATABASE=hop POSTGIS_TEST_USER=hop POSTGIS_TEST_PASSWORD=hop
mvn -s /tmp/hop-ci-settings.xml -U -B -ntp clean verify
```

`BUILD SUCCESS`, **142 Tests, 0 Failures, 0 Errors, 0 Skipped**;
`PostgisDatabaseIntegrationTest` lief echt mit.

Hinweis: Das CI-Image `postgis/postgis:17-3.5` hat auf dem lokalen
`linux/arm64`-Host kein passendes Manifest; es wurde deshalb unter
`--platform linux/amd64` (Emulation) verwendet. Das ist dieselbe CI-Version,
keine andere Datenbankversion.

### Kompatibilität JDK 25

```bash
export JAVA_HOME=/Users/stefan/.sdkman/candidates/java/25.0.2-tem
unset POSTGIS_TEST_ENABLED
mvn -s /tmp/hop-ci-settings.xml -B -ntp test
```

`BUILD SUCCESS`, 142 Tests, 0 Failures, 0 Errors, 1 skipped (PostGIS).

### Distribution

```bash
python3 -m unittest discover -s scripts/tests -v
bash scripts/check-distribution.sh
```

`Ran 6 tests ... OK`; Distributioncheck OK, genau ein ZIP, gültiges
Geometry-/JTS-Layout, keine Datenbanktreiber.

### Übergabe-Repository

Ein separater Konsumenten-Compile gegen
`handover/stage-b/maven-repo` (file-Repository, frisches lokales Maven-Repo)
war erfolgreich. Das aufgelöste JAR hat exakt
`d6b44299e0dc307211a94b40aed99d821b037d610014c3841e7d33f1fa73853a` –
also das hier gebaute Artefakt, nicht der alte Remote-Snapshot.

## 5. Abweichungen und Entscheidungen

1. **Keine OST-Codeübernahme.** Das Archiv wurde geprüft, aber der Parser wurde
   eigenständig nach dem Funktionsvertrag implementiert. Dadurch entsteht keine
   zusätzliche Lizenz-/Herkunftslast; die Quellenreferenz ist dennoch
   dokumentiert.
2. **`CurveWkbReader.read(byte[])` bleibt unchecked.** Das erhält bestehende
   Aufrufer und den vorhandenen Malformed-Test. Die checked API ist
   `readChecked(byte[])`.
3. **Heterogene Multi-Layouts.** `MULTICURVE`/`MULTISURFACE` und
   `GEOMETRYCOLLECTION` können je Mitglied verschiedene Layouts tragen. WKB und
   native Pfade erhalten sie; WKT schreibt bei `MULTICURVE`/`MULTISURFACE`
   typisierte Mitglieder. Für `MULTIPOINT`/`MULTILINESTRING`/`MULTIPOLYGON`
   ist eine heterogene WKT-Darstellung nicht standardkonform und wird mit
   `GC_UNSUPPORTED_LAYOUT` abgelehnt; WKB bleibt möglich.
4. **Leere Kurventypen.** `CIRCULARSTRING`, `COMPOUNDCURVE` und `CURVEPOLYGON`
   ohne Kontrollpunkte werden mit `GC_UNSUPPORTED_EMPTY_CURVE` abgelehnt.
   `MULTICURVE EMPTY`/`MULTISURFACE EMPTY` sind in XY darstellbar; mit
   deklarierter Dimension werden sie abgelehnt.
5. **Lazy Geometry-Storage.** `STORAGE_TYPE_BINARY_STRING` wird für
   `getBinary` nicht als WKB interpretiert, sondern klar abgelehnt. Die
   tatsächlich unterstützten NORMAL-/INDEXED-Pfade sind getestet.
6. **PostGIS-Textausgabe.** `PostgisGeometrySupport.write` verwendet jetzt den
   gemeinsamen layoutbewussten EWKT-Writer. Die bestehenden PostGIS-Tests
   bleiben unverändert grün; Z/M und kurvenhaltige Collections werden nicht
   mehr über die lineare JTS-Sicht verloren.
7. **`GeometryUtils`-Parser.** Der Legacy-Wrapper verwendet den gemeinsamen
   Parser und unterstützt dadurch auch Kurven-WKT; die sichtbare
   EMPTY→NULL-Semantik bleibt unverändert. Das ist eine additive
   Fähigkeitserweiterung, keine stille Änderung der Leerheitsregel.

## 5a. Nachtrag: Legacy-Linienlayout für bestehende Consumer

Beim ersten Lauf der Geoprocessing-Core-Tests gegen das Stage-B-Artefakt schlug
`TrueCurveSupportTest.parserPreservesAllSupportedTrueCurveTypes` fehl. Bestehende
Consumer konstruieren `CompoundCurve`/`CurvePolygon`-Komponenten häufig als
JTS-`LineString` aus `Coordinate[]`: Diese `CoordinateArraySequence` deklariert
Dimension 3, enthält aber nur NaN-Z-Werte. Der strikte Codec behandelte sie als XYZ,
während die Kurvenkontrollpunkte XY waren.

Die Kompatibilität wurde im Geometry-Type-Codec gezielt wiederhergestellt:

- `GeometryLayoutSupport.lineStringLayout(...)` behandelt nur **legacy**
  `CoordinateArraySequence`-Linien/-Polygone mit ausschließlich NaN-Z als XY.
- Explizite, vom Codec erzeugte Packed-Sequenzen behalten deklarierte XYZ/XYM/XYZM
  auch bei all-NaN-Werten.
- WKT-/WKB-Writer verwenden für Linien/Polygone dasselbe effektive Layout für Header
  und Nutzlast.
- Neuer Regressionstest `LegacyCurveLayoutCompatibilityTest` deckt CompoundCurve,
  MultiCurve, MultiSurface und explizite all-NaN-Z-Layouts ab.

Die Geometry-Suite umfasst damit 142 Tests; das Stage-B-Artefakt wurde neu gebaut
(JAR `d6b44299…`, ZIP `00638751…`). Die Geoprocessing-Core-Baseline mit diesem
Artefakt ist wieder grün (64 Tests).

## 6. Offene Punkte / nicht ausgeführt

Nicht Bestandteil von Paket B und daher nicht behauptet:

- `GeometryConverterMeta`, Transform-Runtime und SWT-Dialog (Pakete C/D);
- installierte Hop-Pipelines mit dem neuen Converter, echte Select-Values-
  Runtime und Fehlerhop (Paket E);
- Classloader-/JTS-Identitätsnachweis im installierten Stack;
- E2E-/PostGIS-Installed-Hop-Pfade (Paket E);
- Veröffentlichung, Push oder Deployment.

Die nächste Etappe muss gegen das Übergabeartefakt in
[`../../handover/stage-b`](../../handover/stage-b) arbeiten; Details stehen in
[`handover/stage-b/README.md`](../../handover/stage-b/README.md).

## 7. Übergabe an das Geoprocessing-Arbeitspaket

Verfügbare API:

- `GeometryCodec`, `GeometryReadLimits`
- `GeometryTextCodec`, `GeometryBinaryCodec`, `GeometryHexCodec`
- `GeometryLayoutSupport`, `GeometrySridSupport`, `PointCoordinateSupport`
- `CoordinateLayout`, `PointCoordinates`
- `WkbWriteOptions`, `WktWriteOptions`, `WkbDialect`, `GeometryByteOrder`,
  `SridEncoding`
- `GeometryCodecException`, `GeometryCodecErrorCode`

Benötigte Artefakte:

- `handover/stage-b/maven-repo/ch/so/agi/hop-geometry-type/0.2.0-SNAPSHOT/`
- `handover/stage-b/maven-repo/ch/so/agi/hop-geometry-type-parent/0.2.0-SNAPSHOT/`
- `handover/stage-b/hop-geometry-type-plugin-0.2.0-SNAPSHOT.zip`

Noch zu erbringende Integrationsnachweise für Paket C–E: Schemacompiler,
Runtime, Transform, Dialog, installierte Pipelines, Classloaderidentität,
Fehlerhop, Zeilenbilanz und E2E.
