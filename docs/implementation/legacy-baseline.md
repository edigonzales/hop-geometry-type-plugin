# Legacy-Baseline-Fixtures (vor Etappe 1)

Diese Werte wurden aus dem unveränderten Build am HEAD
`94bc4d90b2c51f1fc85c9498b2993707ccb1fb14` erzeugt. Sie sind der
Bestandsschutz für Standard-Stringdarstellung, SRID-Präfix, WKB-Ausgabe und
Hop-Stream-Rahmung. Die Fixtures werden nicht mit dem neuen Writer neu
erzeugt, sondern als feste Erwartungswerte geprüft.

## Erzeugung

Ein temporäres Java-Programm wurde gegen
`hop-geometry-type/target/classes` und den mit Maven aufgelösten
Test-Classpath kompiliert. Das Programm verwendete:

- `new ValueMetaGeometry("geom")`
- `ValueMetaGeometry.getString(...)`
- `CurveGeometrySupport.writeWkb(...)`
- `ValueMetaGeometry.writeData(new DataOutputStream(...), ...)`
- `GeometryUtils.getGeometryFromEWKT(...)`
- als Kurvenfixture die vorhandene CurvePolygon-WKB aus
  `ValueMetaGeometryTest` (SRID 2056).

## Textausgaben

| Fall | Erwartung |
|---|---|
| `POINT (1 2)` | `POINT (1 2)` |
| `POINT Z (1 2 3)` | `POINT Z(1 2 3)` |
| `POINT (1 2)` mit SRID 2056 | `SRID=2056;POINT (1 2)` |
| CurvePolygon-WKB mit SRID 2056 | `SRID=2056;CURVEPOLYGON (CIRCULARSTRING (0 0, 4 0, 4 4, 0 4, 0 0))` |

## WKB-Ausgaben (`CurveGeometrySupport.writeWkb`, Hex lowercase)

| Fall | Erwartung |
|---|---|
| POINT XY (JTS-WKTReader-Sequenz) | `00800000013ff000000000000040000000000000007ff8000000000000` |
| POINT XY SRID 2056 | `00a0000001000008083ff000000000000040000000000000007ff8000000000000` |
| CurvePolygon SRID 2056 | `010a00002008080000010000000108000000050000000000000000000000000000000000000000000000000010400000000000000000000000000000104000000000000010400000000000000000000000000000104000000000000000000000000000000000` |

Die erste Zeile dokumentiert bewusst das bisherige Verhalten des linearen
Legacy-Pfads: Der JTS-`WKTReader` legt für unmarkiertes XY intern eine
dreidimensionale Sequence mit NaN-Z an; `LinearGeometryCodec` schreibt deshalb
ein EWKB-Z-Flag. Der neue strikte Codec erzeugt für explizit geparstes XY
dagegen ein echtes XY-Layout. Die Legacy-Ausgabe selbst bleibt unverändert.

## Hop-Stream-Rahmung (`ValueMetaGeometry.writeData`, Hex lowercase)

| Fall | Erwartung |
|---|---|
| POINT XY | `000000001d00800000013ff000000000000040000000000000007ff8000000000000` |
| POINT XY SRID 2056 | `000000002100a0000001000008083ff000000000000040000000000000007ff8000000000000` |
| CurvePolygon SRID 2056 | `0000000066010a00002008080000010000000108000000050000000000000000000000000000000000000000000000000010400000000000000000000000000000104000000000000010400000000000000000000000000000104000000000000000000000000000000000` |

Rahmung: `boolean NULL-Flag`, bei Wert `int Länge` (Big Endian) und WKB. Diese
Rahmung bleibt unverändert und ist nicht Teil der neuen Codec-Nutzdaten.

## Legacy-EMPTY und EWKT

| Fall | Erwartung |
|---|---|
| `GeometryUtils.getGeometryFromEWKT("POINT EMPTY")` | `null` |
| `GeometryUtils.getGeometryFromEWKT("SRID=2056;POINT(1 2)")` | SRID 2056 |

Der neue `GeometryCodec.readText("POINT EMPTY")` liefert dagegen ein leeres
Point-Objekt. Diese Trennung ist verbindlich.
