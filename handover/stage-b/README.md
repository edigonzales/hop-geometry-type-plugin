# Übergabe Etappe B – gemeinsame Geometry-Codec-API

Dieses Verzeichnis enthält die tatsächlich gebauten und getesteten Artefakte aus
`hop-geometry-type-plugin`, Stand 2026-10-17. Es ist kein neues Plugin und kein
zusätzliches Installationspaket: Das ZIP ist das bestehende Geometry-Type-ZIP,
das JAR/POM sind die bestehende gemeinsame Maven-Laufzeit.

## Artefakte

| Datei | SHA-256 |
|---|---|
| `hop-geometry-type-plugin-0.2.0-SNAPSHOT.zip` | `00638751fcae5637cb9fc3db5db6ec8bd02601347394eb67ada58110332721a3` |
| `maven-repo/.../hop-geometry-type-0.2.0-SNAPSHOT.jar` | `d6b44299e0dc307211a94b40aed99d821b037d610014c3841e7d33f1fa73853a` |
| `maven-repo/.../hop-geometry-type-0.2.0-SNAPSHOT.pom` | `9dcacb8b59d8179cab3b7ec12c9fa2eeccdfe114fbe3b78a03833a64d7f0775c` |
| `maven-repo/.../hop-geometry-type-parent-0.2.0-SNAPSHOT.pom` | `2b03b72a48e35699c6294d7c370d209a9a8d7bcb58dd1814f95645b4154f66fc` |

Die vollständige Liste steht in `SHA256SUMS`. Vor der Verwendung die Hashes
prüfen; nicht auf einen gleichnamigen Remote-Snapshot ausweichen.

## Verwendung im nachfolgenden Geoprocessing-Arbeitspaket

Das Verzeichnis `maven-repo` ist ein lokales Maven-Repository im Standardlayout:

```xml
<repositories>
  <repository>
    <id>stage-b-handover</id>
    <url>file:///ABSOLUTER/PFAD/handover/stage-b/maven-repo</url>
  </repository>
</repositories>
```

```xml
<dependency>
  <groupId>ch.so.agi</groupId>
  <artifactId>hop-geometry-type</artifactId>
  <version>0.2.0-SNAPSHOT</version>
</dependency>
```

Wichtig: Das ist genau das hier gebaute JAR, nicht der ältere Remote-Snapshot.
Für reproduzierbare lokale Läufe entweder dieses Repository verwenden oder die
Dateien mit `mvn install:install-file` in den lokalen Cache übernehmen. Die
Parent-POM liegt im selben Repository.

## Enthaltene gemeinsame API

- `com.atolcd.hop.gis.geometry.codec.GeometryCodec`
- `GeometryTextCodec`, `GeometryBinaryCodec`, `GeometryHexCodec`
- `GeometryLayoutSupport`, `GeometrySridSupport`, `PointCoordinateSupport`
- `CoordinateLayout`, `PointCoordinates`, `GeometryReadLimits`
- `WkbWriteOptions`, `WktWriteOptions`, `WkbDialect`, `GeometryByteOrder`, `SridEncoding`
- `GeometryCodecException`, `GeometryCodecErrorCode`

Die Hop-Feldtyp-Hooks sind in `ValueMetaGeometry` enthalten:

- `convertData(IValueMeta, Object)` für String (WKT/EWKT/Hex) und Binary (WKB/EWKB)
- `getBinary(Object)` für den normalen Binary-Zieltyp
- unveränderte Legacy-Stringdarstellung und unveränderte Hop-Stream-Rahmung
- `GeometryUtils.getGeometryFromEWKT(...)` bleibt bei `EMPTY → NULL`

## Noch offene Integrationsnachweise (nicht Etappe B)

Paket B liefert die gemeinsame API und ihre Tests. Noch nicht ausgeführt bzw.
nicht Bestandteil dieses Artefakts:

- `GeometryConverterMeta`, Transform-Runtime und SWT-Dialog (Pakete C/D);
- echte installierte Hop-Pipelines mit Select Values und dem neuen Converter;
- Classloader-/JTS-Identitätsnachweis im installierten Stack;
- Fehlerhop-, E2E- und PostGIS-Installed-Hop-Pfade (Paket E);
- Veröffentlichung, Push oder Deployment (ausdrücklich nicht beauftragt).

Details, Befehle und Testergebnisse:
[`../../docs/implementation/geometry-converter-stage-b.md`](../../docs/implementation/geometry-converter-stage-b.md).
