# Audit Log Core – Leitfaden für Junior-Entwickler

Dieser Leitfaden erklärt, wie du die Audit-Log-Bibliothek in deiner Java-Anwendung nutzt.

---

## 1. Abhängigkeit einbinden

```xml
<dependency>
    <groupId>io.audit</groupId>
    <artifactId>audit-log-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

Benötigte PostgreSQL-Version: **14+** (wegen JSONB).

---

## 2. Datenbank vorbereiten

Führe das Migration-Script auf deiner PostgreSQL-Datenbank aus:

```bash
psql -U dein_user -d deine_db -f src/main/resources/db/migration/V1__create_audit_log_table.sql
```

Oder nutze Flyway (empfohlen). Die Migration liegt unter `db/migration/V1__create_audit_log_table.sql`.

---

## 3. Logger erstellen

### Variante A: Mit vorhandenem DataSource (empfohlen)

Wenn deine Anwendung bereits einen Connection-Pool hat (z. B. aus Quarkus, Spring Boot): 

```java
@Inject
DataSource dataSource;  // Dein vorhandener Pool

var logger = new PostgresAuditLogger(dataSource);
```

Der Logger nimmt **jede** `javax.sql.DataSource` – HikariCP, DBCP, Tomcat Pool, egal.

### Variante B: Mit eigener JDBC-URL

Wenn du noch keinen Connection-Pool hast (braucht HikariCP auf dem Classpath):

```java
var logger = PostgresAuditLoggers.create(
    "jdbc:postgresql://localhost:5432/mydb",
    "mein_user",
    "mein_passwort"
);
```

Der Logger erzeugt dann automatisch einen HikariCP-Pool mit:
- max. 5 Verbindungen
- min. 1 Verbindung idle
- Pool-Name: `audit-log-pool`

---

## 4. Audit-Eintrag bauen

Der Builder stellt sicher, dass alle Pflichtfelder gesetzt sind:

```java
AuditEntry entry = AuditEntry.builder()
    .actorId("user-42")           // Wer? (Pflicht)
    .action("UPDATE")              // Was? (Pflicht)
    .entityType("Order")           // Welche Entität? (Pflicht)
    .entityId("ord-123")           // Welche ID? (Pflicht)
    .build();                      // Fertig
```

### Optionale Felder

```java
AuditEntry entry = AuditEntry.builder()
    .actorId("user-42")
    .action("UPDATE")
    .entityType("Order")
    .entityId("ord-123")

    // ID selbst setzen (sonst automatisch generiert)
    .id(UUID.randomUUID())

    // Zeitpunkt selbst setzen (sonst automatisch: jetzt)
    .timestamp(OffsetDateTime.now())

    // Änderungen im JSONB-Format
    .changes(Map.of(
        "status", Map.of("old", "PENDING", "new", "SHIPPED"),
        "total", Map.of("old", 99.0, "new", 129.0)
    ))

    // Metadaten als JSONB
    .metadata(Map.of(
        "source_ip", "192.168.1.1",
        "correlation_id", "req-789"
    ))

    .build();
```

### Validierung – was passiert bei fehlenden Pflichtfeldern?

Der Builder wirft `IllegalArgumentException`, wenn:

```java
.actorId(null)       // → IllegalArgumentException: actorId must not be blank
.actorId("  ")       // → IllegalArgumentException: actorId must not be blank
.action("")          // → IllegalArgumentException: action must not be blank
// entityType, entityId genauso
```

`changes` und `metadata` sind optional. Bei `null` werden sie automatisch zu `{}`.

---

## 5. Eintrag loggen

```java
logger.log(entry);
```

Der Aufruf ist **asynchron** – er kehrt sofort zurück und schreibt im Hintergrund auf Virtual Threads in die Datenbank.

### Auf Fertigstellung warten

```java
// Blockieren, bis der Eintrag geschrieben ist:
logger.log(entry).join();

// Oder mit Timeout:
logger.log(entry).get(5, TimeUnit.SECONDS);
```

### Mehrere Einträge gleichzeitig

```java
CompletableFuture.allOf(
    logger.log(entry1),
    logger.log(entry2),
    logger.log(entry3)
).join();  // Wartet, bis ALLE fertig sind
```

---

## 6. Fehler behandeln

### try-catch

```java
try {
    logger.log(entry).join();
} catch (AuditLoggingException e) {
    log.warn("Audit-Log fehlgeschlagen: {}", e.getMessage());
    // Entscheide selbst: Ist ein fehlgeschlagenes Audit-Log ein
    // Showstopper oder kann die Anwendung trotzdem weiterlaufen?
}
```

### Was kann schiefgehen?

| Fehler | Ursache | Folge |
|---|---|---|
| DB nicht erreichbar | Netzwerk, Postgres-Ausfall | `AuditLoggingException` in `.join()` |
| JSON-Serialisierung | Zirkelbezüge in changes/metadata | `AuditLoggingException` in `.join()` |
| Zu langer String (>255 Zeichen) | actorId/action/entityType/entityId | `AuditLoggingException` via SQLException |
| Connection-Pool leer | Zu viele gleichzeitige Anfragen | `AuditLoggingException` (Timeout) |

**Wichtig:** Wenn du `.join()` oder `.get()` nicht aufrufst, wird der Fehler **stumm geschluckt**. Das Logging im Hintergrund fängt ihn zwar, aber deine Anwendung bekommt nichts mit.

### Error-Callback für asynchrone Fehler

Wer bei jedem Fehler benachrichtigt werden will (auch ohne `.join()`):

```java
PostgresAuditLogger logger = new PostgresAuditLogger(dataSource, 5,
    PostgresAuditLogger.BackpressurePolicy.FAST_FAIL,
    error -> log.warn("Audit-Log fehlgeschlagen: {}", error.getMessage()));
```

Der Callback wird bei SQL-Fehlern und bei Backpressure-Ablehnung aufgerufen.

---

## 7. Backpressure (Rückstau-Steuerung)

Bei hoher Last können mehr Log-Anfragen eingehen, als der Connection-Pool bedienen kann.
Zwei Strategien:

```java
// MAXIMAL 5 GLEICHZEITIGE LOG-VORGÄNGE
// Blockiert den Aufrufer, bis ein Permit frei wird
PostgresAuditLogger logger = new PostgresAuditLogger(dataSource, 5);
```

```java
// STATTDESSEN SOFORT FEHLSCHLAGEN
PostgresAuditLogger logger = new PostgresAuditLogger(
    dataSource, 5, BackpressurePolicy.FAST_FAIL);
// log() gibt eine failed CompletableFuture zurück – kein Blockieren
```

### Wann welche Policy?

| Policy | Wann sinnvoll |
|---|---|
| `BLOCK` (Default) | Kritische Audit-Events, die auf jeden Fall durch müssen. Der Aufrufer wartet, bis die DB wieder frei ist. |
| `FAST_FAIL` | High-Volume-Logging, bei dem ein verlorener Eintrag akzeptabel ist. Die Anwendung bleibt latency-stabil. |

Ohne Backpressure-Konfiguration ist die Parallelität unbegrenzt – dann schützt nur der Connection-Timeout vor Überlast.

---

## 8. Logger schließen

Immer schließen, wenn die Anwendung herunterfährt:

```java
logger.close();  // Schließt den Connection-Pool
```

Am besten in einem `@PreDestroy` oder `finally`-Block:

```java
// try-with-resources (AutoCloseable)
try (var logger = new PostgresAuditLogger(dataSource)) {
    logger.log(entry).join();
    // logger.close() wird automatisch aufgerufen
}
```

---

## 9. Komplette Beispiele

### Minimal (ein Eintrag)

```java
var logger = PostgresAuditLoggers.create(
    "jdbc:postgresql://localhost:5432/mydb", "user", "pass");

var entry = AuditEntry.builder()
    .actorId("system")
    .action("STARTUP")
    .entityType("Application")
    .entityId("my-app-1")
    .build();

logger.log(entry).join();
logger.close();
```

### Typischer Service (z. B. in Quarkus)

```java
import io.audit.core.AuditEntry;
import io.audit.core.PostgresAuditLogger;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;

@ApplicationScoped
public class AuditService {

    private final PostgresAuditLogger logger;

    @Inject
    public AuditService(DataSource dataSource) {
        this.logger = new PostgresAuditLogger(dataSource);
    }

    public void logOrderUpdate(String orderId, String userId,
                               Map<String, Object> changes) {
        var entry = AuditEntry.builder()
                .actorId(userId)
                .action("UPDATE")
                .entityType("Order")
                .entityId(orderId)
                .changes(changes)
                .build();
        logger.log(entry);  // fire-and-forget (kein .join())
    }

    public void logCreate(String actor, String type, String id) {
        var entry = AuditEntry.builder()
                .actorId(actor)
                .action("CREATE")
                .entityType(type)
                .entityId(id)
                .build();
        logger.log(entry).join();  // blockieren, bis geschrieben
    }

    @PreDestroy
    void cleanup() {
        logger.close();
    }
}
```

### Batch-Import mit Fehlerbehandlung

```java
public void importAll(List<AuditEntry> entries) {
    var futures = entries.stream()
            .map(logger::log)
            .toArray(CompletableFuture[]::new);

    try {
        CompletableFuture.allOf(futures).join();
    } catch (CompletionException e) {
        log.error("Batch-Import fehlgeschlagen nach {} erfolgreichen Einträgen",
            Arrays.stream(futures).filter(CompletableFuture::isDone).count(), e);
        throw new RuntimeException("Audit-Batch-Import fehlgeschlagen", e.getCause());
    }
}
```

---

## 10. Best Practices für den Alltag

### Fire-and-Forget vs. Sync

```java
// OK: Wenn ein fehlgeschlagenes Audit-Log nicht kritisch ist
logger.log(entry);

// Besser: Wenn du wissen musst, ob es geklappt hat
logger.log(entry).join();
```

Bei Audit-Logs gilt: **Lieber ohne Rückmeldung als die Anwendung aufhalten.** Standardmäßig `fire-and-forget`, nur bei Compliance-relevanten Events auf Bestätigung warten.

### changes und metadata richtig nutzen

```java
// FÜR ÄNDERUNGSNACHVERFOLG: Vorher/Nachher
.changes(Map.of(
    "email", Map.of("old", "alice@old.com", "new", "alice@new.com"),
    "role", Map.of("old", "USER", "new", "ADMIN")
))

// FÜR KONTEXT: Technische Metadaten
.metadata(Map.of(
    "source_ip", "192.168.1.1",
    "user_agent", "Mozilla/...",
    "correlation_id", "req-abc-123",
    "tenant_id", "customer-456"
))
```

### Strings nicht länger als 255 Zeichen

`actorId`, `action`, `entityType` und `entityId` sind per Datenbank-Schema auf `VARCHAR(255)` begrenzt. Längere Werte führen zu einem SQL-Fehler. Kürze sie vorher:

```java
var safeActorId = longActorId.length() > 255
    ? longActorId.substring(0, 255)
    : longActorId;
```

### ID-Kollision vermeiden

Der Builder generiert automatisch eine UUID. Wenn du eigene IDs setzt, achte auf Eindeutigkeit – die DB hat einen Primary-Key-Constraint.

---

## 11. Datenbank-Schema

```sql
CREATE TABLE IF NOT EXISTS audit_log (
    id          UUID            PRIMARY KEY,   -- Eindeutige ID
    timestamp   TIMESTAMPTZ     NOT NULL,       -- Zeitstempel (mit Zeitzone)
    actor_id    VARCHAR(255)    NOT NULL,       -- Wer?
    action      VARCHAR(255)    NOT NULL,       -- Was?
    entity_type VARCHAR(255)    NOT NULL,       -- Welche Entität?
    entity_id   VARCHAR(255)    NOT NULL,       -- Welche ID?
    changes     JSONB,                          -- Vorher/Nachher (optional)
    metadata    JSONB                           -- Zusatzinfos (optional)
);

-- Indizes für häufige Abfragen
CREATE INDEX idx_audit_log_timestamp ON audit_log (timestamp);
CREATE INDEX idx_audit_log_actor     ON audit_log (actor_id);
CREATE INDEX idx_audit_log_entity    ON audit_log (entity_type, entity_id);
CREATE INDEX idx_audit_log_action    ON audit_log (action);
```

---

## 12. Zusammenfassung: Ein Satz pro Konzept

| Konzept | Merksatz |
|---|---|
| Logger erstellen | `new PostgresAuditLogger(dataSource)` oder `PostgresAuditLoggers.create(jdbcUrl, user, pass)` |
| Entry bauen | `AuditEntry.builder().actorId(...).action(...).entityType(...).entityId(...).build()` |
| Loggen | `logger.log(entry)` ist asynchron, `.join()` wartet auf Fertigstellung |
| Fehler | `AuditLoggingException` – tritt erst bei `.join()` auf |
| Schließen | `logger.close()` am Ende, am besten via try-with-resources |
| Backpressure | `new PostgresAuditLogger(dataSource, 5)` für max. 5 gleichzeitige Logs, `FAST_FAIL` für sofortigen Fehler |
| Error-Callback | Via `Consumer<AuditLoggingException>` im Konstruktor – wird bei jedem Fehler aufgerufen |
| Executor | Wird von `close()` **nie** shutdown – bleibt in Verantwortung des Aufrufers |
