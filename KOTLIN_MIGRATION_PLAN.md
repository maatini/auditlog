# Kotlin-Migrationsplan für audit-log-core

**Branch:** `kotlin-version`  
**Status:** Plan erstellt, noch keine Code-Änderungen  
**Zielversion:** Kotlin 2.1.x + JVM 21 Target

---

## 1. Zielsetzung

Vollständige, saubere Konvertierung der Java 21 Bibliothek nach Kotlin unter **vollständiger Wahrung der Java-Interoperabilität**.

- Alle bestehenden Java-Konsumenten (inkl. `AuditEntry.builder()...`, `PostgresAuditLoggers.builder()`) müssen **ohne Code-Änderung** weiter funktionieren.
- Das Verhalten (asynchrone Virtual-Thread-Persistierung, Backpressure via Semaphore, SHA-256 Chain-Hash, JSONB-Serialisierung, Append-Only Trigger) bleibt **bitgenau** identisch.
- Build, Tests, JaCoCo (≥85 %), Pitest (≥40 %), OWASP und Demo müssen weiterhin über Devbox laufen.

---

## 2. Warum Kotlin?

| Aspekt                  | Java 21                          | Kotlin 2.1                          | Relevanz für dieses Projekt          |
|-------------------------|----------------------------------|-------------------------------------|--------------------------------------|
| Null-Safety             | Optional / @Nullable            | Built-in                            | Hohe Robustheit bei DB/JSON          |
| Boilerplate             | Records + manuelle Builder      | `data class` + Builder (optional)   | AuditEntry & zwei komplexe Builder   |
| Asynchrone APIs         | CompletableFuture + VT          | Gleich + bessere `when`/`also`      | Weniger Fehler im Log-Pfad           |
| Daten-Modell            | Map<String,Object> + Record     | Map<String,Any?> + data class       | JSONB-Fokus (Data-Centric)           |
| Interop                 | —                               | Exzellent (von Java aus)            | Bibliotheks-Charakter                |
| Zukünftige Erweiterung  | —                               | Kotlin DSL, Extension Functions     | Niedrigschwellige AuditEntry-Erzeugung |

**Wichtige Design-Entscheidung:** Es wird **keine** `kotlinx.coroutines` als Pflichtabhängigkeit eingeführt. Die öffentliche API bleibt bei `CompletableFuture<Void>` + Virtual Threads. Coroutines können später als optionales Add-on angeboten werden.

---

## 3. Technische Rahmenbedingungen (Ground Truth)

- **Kotlin-Version:** 2.1.0 (oder neueste 2.1.x bei Umsetzungsbeginn)
- **kotlin-maven-plugin:** 2.1.0, `jvmTarget=21`, `javaParameters=true`
- **Quellverzeichnisse:** `src/main/kotlin`, `src/test/kotlin` (Standard des Plugins)
- **Abhängigkeiten:**
  - `kotlin-stdlib` (Pflicht)
  - `kotlin-reflect` **nur wenn** nötig (aktuell nicht geplant)
  - `jackson-module-kotlin` als **optionale** Dependency (für User, die echte Kotlin-Datenklassen in `changes`/`metadata` serialisieren wollen)
- **HikariCP, PostgreSQL Driver, Jackson Databind, SLF4J, Flyway** bleiben exakt gleich.
- **Test-Stack** bleibt JUnit Jupiter + Mockito (bewährte Kombination mit Kotlin). Kein Wechsel zu Kotest/MockK in Phase 1–5, um Risiko zu minimieren.
- **JMH Benchmark:** Bekannt schwierig mit Kotlin + Annotation Processing. Mögliche Strategien:
  - Benchmark-Klasse in Java belassen (gute Interim-Lösung)
  - Oder separate `jmh` Source-Roots mit Java
- Alle Maven-Befehle **ausschließlich** über Devbox (`devbox run build`, `echo 'mvn ...' | devbox shell` etc.).

---

## 4. Phasenplan (strikt inkrementell – Small Steps)

### Phase 0 – Fundament (aktuell)
- [x] Branch `kotlin-version` erstellt
- [ ] `pom.xml` um `kotlin-maven-plugin` + `kotlin-stdlib` erweitern
- [ ] `src/main/kotlin/io/audit/core/` und Test-Verzeichnis anlegen (leere `.gitkeep`)
- [ ] Erster Build (`devbox run build:no-tests`) muss mit 0 Kotlin-Dateien grün sein
- [ ] Diese Plan-Datei committen

**Exit-Kriterium:** `mvn clean compile` (via Devbox) erfolgreich.

### Phase 1 – Triviale Klassen & Interface
1. `AuditLoggingException.java` → `AuditLoggingException.kt`
2. `AuditLogger.java` → `AuditLogger.kt` (Interface mit Default-Methode)
3. Java-Dateien löschen, Build + kurze Unit-Tests

**Exit-Kriterium:** `mvn test -Dtest=AuditLoggerTest` grün.

### Phase 2 – Datenmodell (höchste Priorität – Data-Centric)
- `AuditEntry.java` → `AuditEntry.kt`
  - `data class AuditEntry(...)`
  - `companion object { @JvmStatic fun builder() = Builder() }`
  - `class Builder { ... }` (exakt gleiche Fluent-API + Validierung)
  - `MAX_STRING_LENGTH`, `requireNotBlank`, unmodifiable Maps **bitgenau** nachbauen
- **Vollständige** `AuditEntryTest` muss 100 % bestehen (inkl. 255-Zeichen-Grenze, Blank-Checks, Default-ID/Timestamp)

**Exit-Kriterium:** `mvn test -Dtest=AuditEntryTest` + manuelle visuelle Prüfung der Builder-Nutzung in IntelliJ/Eclipse von Java aus.

### Phase 3 – Kern-Implementierung (PostgresAuditLogger)
Größte Datei, daher **interne** Unterteilung:

3.1 Konstanten, `OBJECT_MAPPER`, `DEFAULT_EXECUTOR`, SQL-Strings  
3.2 Konstruktoren + `Builder` (inkl. `BackpressurePolicy` als Kotlin `enum class`)  
3.3 `log()`, `acquirePermit()`, `releasePermitAndNotify()` (Semaphore-Logik)  
3.4 `executeInsert()`, `insert()`, `queryPrevHash()`, `computeChainHash()`  
3.5 `toJson()`, `close()` + Ressourcen-Management (`use` wo sinnvoll)  

Kotlin-Vorteile hier nutzen:
- `when (backpressurePolicy)` statt if
- `entry.id?.toString() ?: ...` (smart casts)
- String-Templates für bessere Lesbarkeit (aber SQL bleibt Text-Block)

**Exit-Kriterium:** Alle `PostgresAuditLoggerTest` (inkl. Backpressure, ErrorCallback, ObjectMapper-Custom, Close-Verhalten) grün.

### Phase 4 – Factory (PostgresAuditLoggers)
- `PostgresAuditLoggers.java` → Kotlin `object PostgresAuditLoggers`
- Builder-Klasse mit vielen optionalen Feldern
- Achtung auf qualifizierte Aufrufe `io.audit.core.PostgresAuditLogger.builder()` – nach Konvertierung von Phase 3 auflösen

**Exit-Kriterium:** `PostgresAuditLoggersTest` vollständig grün.

### Phase 5 – Tests, Integration, Benchmark
- Alle verbleibenden `*.java` Testklassen nach Kotlin portieren (oder schrittweise)
- `PostgresAuditLoggerIntegrationTest` (Testcontainers) – besonders kritisch
- `AuditLoggerBenchmark` (JMH): siehe Risiken oben. Entweder Java belassen oder spezielle Maven-Konfiguration
- `mvn verify` (JaCoCo + Pitest) muss die Schwellwerte weiter einhalten

### Phase 6 – Beispiel & Dokumentation
- `example/` Modul: Java-Demo **bleibt Java** (beweist echte Interop)
- Optional: `example-kotlin/` Modul mit Kotlin-DSL-Demo (additiv)
- README.md:
  - Kotlin-Badge hinzufügen
  - Mindestens ein Kotlin-Code-Beispiel (Builder + `PostgresAuditLoggers`)
- USAGE.md, docs/diagrams/ (falls nötig), CLAUDE.md aktualisieren (Tech-Stack: Kotlin 2.1 + Java 21)
- `run_demo.sh` ggf. anpassen

### Phase 7 – Finale Qualität & Release-Vorbereitung
- Voller `devbox run build`
- Manuelles Demo-Start (`devbox run run:demo`)
- OWASP Dependency-Check
- Kompatibilitätstest: externes reines Java-Maven-Projekt, das die gebaute JAR referenziert
- `CHANGELOG.md` oder Release-Notes für 2.0.0-Kotlin
- Version auf `2.0.0` oder `1.1.0-kotlin` (Entscheidung offen)

---

## 5. Risiken & bekannte Fallstricke

1. **JMH + Kotlin Annotation Processing**  
   Häufiges Problem. **Mitigation:** Benchmark-Datei zunächst als `.java` belassen (im `src/test/java` Verzeichnis). Das ist pragmatisch und erfüllt "Small Steps".

2. **HikariDataSource Casts & `AutoCloseable`**  
   In `closeDataSource()` und `closeExecutor()` gibt es `instanceof` + Cast. In Kotlin: `is` + Smart-Cast. Verhalten muss identisch bleiben.

3. **Jackson Serialisierung von `Map<String, Object>` vs `Map<String, Any?>`**  
   Da aktuell nur `Map<String, Object>` befüllt wird und Jackson identisch konfiguriert bleibt, ist das unkritisch. Spätere Kotlin-DSL kann `Any?` verwenden.

4. **Deterministischer Chain-Hash**  
   `String.getBytes()` (Default = UTF-8) + `MessageDigest` muss exakt dieselben Bytes liefern. Keine Kotlin-String-Operationen einführen, die Charset oder Whitespace ändern.

5. **Devbox / Maven Reactor**  
   Nach Umstellung auf `src/main/kotlin` muss sichergestellt sein, dass der kotlin-maven-plugin vor `maven-compiler-plugin` läuft (Standard-Konfiguration reicht normalerweise).

6. **Git History**  
   Große Konvertierung = schlechte Blame. Plan: am Ende der Migration ggf. `git filter-branch` oder einfach akzeptieren (üblich bei vollständigen Rewrites).

---

## 6. Kotlin-Verbesserungen (nach erfolgreicher Grundkonvertierung, nicht im Scope der ersten 5 Phasen)

- **AuditEntry DSL** (Kotlin-only, Java sieht es nicht):
  ```kotlin
  val entry = AuditEntry {
      actorId = "user-42"
      action = "UPDATE"
      entityType = "Order"
      entityId = "ord-123"
      changes = mapOf("status" to mapOf("old" to "PENDING", "new" to "SHIPPED"))
  }
  ```
- Extension-Functions: `AuditEntry.asJson()` oder `AuditLogger.logSuspending(...)` (für Coroutine-User)
- `sealed interface` für interne Policies (falls sinnvoll)
- Bessere `Result<AuditEntry>` oder eigene Error-Klasse intern (öffentliche API bleibt bei Exceptions)

---

## 7. Verifikations-Checkliste (nach jeder Phase)

- [ ] `echo 'mvn clean test' | devbox shell` → grün
- [ ] `devbox run build` → JaCoCo + Pitest grün
- [ ] `devbox run run:demo` → erfolgreich
- [ ] Alle bestehenden Java-Beispiele in README/USAGE kompilieren und laufen (von Java aus)
- [ ] Keine neuen Laufzeit-Abhängigkeiten (außer kotlin-stdlib)

---

## 8. Nächste konkrete Schritte (nach Plan-Freigabe)

1. Phase 0 umsetzen (pom.xml + erste leere Kotlin-Struktur)
2. Commit `Phase 0: Kotlin toolchain eingerichtet`
3. Phase 1 starten
4. Regelmäßige Rebase / Merge von `main` (falls Hotfixes kommen)

---

**Prinzipien dieses Plans** (entsprechen den Agenten-Regeln):
- Read Before You Write → komplette Code-Basis wurde analysiert (Records, doppelte Builder, Semaphore-Backpressure, Chain-Hash, Jackson-Mapper, Testcontainers)
- Small, Incremental Steps → 8 Phasen mit klaren Exit-Kriterien
- Ground Truth → keine geratene Kotlin-Maven-Config; wird in Phase 0 verifiziert
- Data-Centric → AuditEntry kommt als **Phase 2** sehr früh
- High Signal-to-Noise → dieser Plan ist bewusst knapp und entscheidungsorientiert

---

*Erstellt auf Branch `kotlin-version` – 2026*