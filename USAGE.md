# Audit Log Core – Leitfaden für Junior-Entwickler

Dieser Leitfaden zeigt dir Schritt für Schritt, wie du die Audit-Log-Bibliothek
in deiner Java-Anwendung nutzt. Für den schnellen Einstieg siehe [README.md](README.md).

---

## 1. Abhängigkeit & Datenbank

*Details und vollständige Konfiguration: siehe [README.md](README.md).*

```xml
<dependency>
    <groupId>io.audit</groupId>
    <artifactId>audit-log-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

PostgreSQL 14+ nötig (JSONB). Schema per Flyway-Migration oder manuelles SQL-Script
anlegen – siehe README.

---

## 2. Logger erstellen

### Variante A: Vorhandener DataSource (empfohlen)

```java
@Inject
DataSource dataSource;  // Dein vorhandener Pool
var logger = new PostgresAuditLogger(dataSource);
```

Akzeptiert jede `javax.sql.DataSource` – HikariCP, DBCP, Tomcat Pool, egal.

### Variante B: Eigene JDBC-URL (braucht HikariCP)

```java
var logger = PostgresAuditLoggers.create(
    "jdbc:postgresql://localhost:5432/mydb", "mein_user", "mein_passwort");
```

Erzeugt automatisch einen HikariCP-Pool (max. 5 Connections, min. 1 idle).

---

## 3. Audit-Eintrag bauen

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
    .id(UUID.randomUUID())        // ID selbst setzen (sonst automatisch)
    .timestamp(OffsetDateTime.now())  // Zeitpunkt (sonst: jetzt)
    .changes(Map.of(              // Änderungen als JSONB
        "status", Map.of("old", "PENDING", "new", "SHIPPED"),
        "total", Map.of("old", 99.0, "new", 129.0)))
    .metadata(Map.of(             // Metadaten als JSONB
        "source_ip", "192.168.1.1",
        "correlation_id", "req-789"))
    .build();
```

### Validierung

Der Builder wirft `IllegalArgumentException` bei fehlenden Pflichtfeldern:

```java
.actorId(null)       // → IllegalArgumentException: actorId must not be blank
.actorId("  ")       // → IllegalArgumentException: actorId must not be blank
.action("")          // → IllegalArgumentException: action must not be blank
// entityType, entityId genauso
```

`changes` und `metadata` sind optional. Bei `null` werden sie automatisch zu `{}`.

---

## 4. Eintrag loggen

```java
logger.log(entry);
```

Der Aufruf ist **asynchron** – er kehrt sofort zurück und schreibt im Hintergrund
auf Virtual Threads in die Datenbank.

```java
// Auf Fertigstellung warten:
logger.log(entry).join();

// Mit Timeout:
logger.log(entry).get(5, TimeUnit.SECONDS);

// Mehrere parallel:
CompletableFuture.allOf(
    logger.log(entry1), logger.log(entry2), logger.log(entry3)
).join();
```

---

## 5. Fehler behandeln

```java
try {
    logger.log(entry).join();
} catch (AuditLoggingException e) {
    log.warn("Audit-Log fehlgeschlagen: {}", e.getMessage());
}
```

### Was kann schiefgehen?

| Fehler | Ursache | Folge |
|---|---|---|
| DB nicht erreichbar | Netzwerk, Postgres-Ausfall | `AuditLoggingException` in `.join()` |
| JSON-Serialisierung | Zirkelbezüge in changes/metadata | `AuditLoggingException` in `.join()` |
| Zu langer String (>255 Zeichen) | actorId/action/entityType/entityId | `AuditLoggingException` via SQLException |
| Connection-Pool leer | Zu viele gleichzeitige Anfragen | `AuditLoggingException` (Timeout) |

**Wichtig:** Ohne `.join()` oder `.get()` wird der Fehler **stumm geschluckt**.

### Error-Callback

```java
PostgresAuditLogger logger = PostgresAuditLogger.builder()
    .dataSource(dataSource).maxConcurrency(5)
    .backpressurePolicy(PostgresAuditLogger.BackpressurePolicy.FAST_FAIL)
    .errorCallback(error -> log.warn("Audit-Log fehlgeschlagen: {}", error.getMessage()))
    .build();
```

Wird bei SQL-Fehlern und Backpressure-Ablehnung aufgerufen – auch ohne `.join()`.

---

## 6. Backpressure (Rückstau-Steuerung)

*Konfiguration: siehe README. Hier die Entscheidungshilfe:*

| Policy | Wann sinnvoll |
|---|---|
| `BLOCK` (Default) | Kritische Audit-Events – Aufrufer wartet, bis DB wieder frei ist |
| `FAST_FAIL` | High-Volume-Logging – verlorener Eintrag akzeptabel, Anwendung bleibt latency-stabil |

---

## 7. Logger schließen

```java
logger.close();  // Schließt den Connection-Pool
```

Am besten via try-with-resources (`AutoCloseable`):

```java
try (var logger = new PostgresAuditLogger(dataSource)) {
    logger.log(entry).join();
}
```

---

## 8. Komplette Beispiele

### Typischer Service (z.B. in Quarkus)

```java
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
                .actorId(userId).action("UPDATE")
                .entityType("Order").entityId(orderId)
                .changes(changes).build();
        logger.log(entry);  // fire-and-forget
    }

    @PreDestroy
    void cleanup() { logger.close(); }
}
```

### Batch-Import mit Fehlerbehandlung

```java
public void importAll(List<AuditEntry> entries) {
    var futures = entries.stream().map(logger::log)
            .toArray(CompletableFuture[]::new);
    try {
        CompletableFuture.allOf(futures).join();
    } catch (CompletionException e) {
        log.error("Batch-Import fehlgeschlagen: {} erfolgreich",
            Arrays.stream(futures).filter(CompletableFuture::isDone).count(), e);
        throw new RuntimeException("Audit-Batch-Import fehlgeschlagen", e.getCause());
    }
}
```

---

## 9. Best Practices

### Fire-and-Forget vs. Sync

```java
logger.log(entry);         // OK: Fehler nicht kritisch
logger.log(entry).join();  // Besser: Du musst wissen, ob es geklappt hat
```

Standardmäßig `fire-and-forget`, nur bei Compliance-relevanten Events auf Bestätigung warten.

### changes und metadata richtig nutzen

```java
// ÄNDERUNGSNACHVERFOLG: Vorher/Nachher
.changes(Map.of(
    "email", Map.of("old", "alice@old.com", "new", "alice@new.com"),
    "role", Map.of("old", "USER", "new", "ADMIN")))

// KONTEXT: Technische Metadaten
.metadata(Map.of(
    "source_ip", "192.168.1.1", "user_agent", "Mozilla/...",
    "correlation_id", "req-abc-123", "tenant_id", "customer-456"))
```

### Strings nicht länger als 255 Zeichen

`actorId`, `action`, `entityType`, `entityId` sind `VARCHAR(255)`. Längere Werte kürzen:

```java
var safeId = longId.length() > 255 ? longId.substring(0, 255) : longId;
```

### ID-Kollision vermeiden

Der Builder generiert automatisch eine UUID. Bei eigenen IDs: auf Eindeutigkeit achten.

---

## 10. Zusammenfassung: Ein Satz pro Konzept

| Konzept | Merksatz |
|---|---|
| Logger erstellen | `new PostgresAuditLogger(dataSource)` oder `.builder().dataSource(ds).build()` |
| Entry bauen | `AuditEntry.builder().actorId(...).action(...).entityType(...).entityId(...).build()` |
| Loggen | `logger.log(entry)` ist asynchron, `.join()` wartet auf Fertigstellung |
| Fehler | `AuditLoggingException` – tritt erst bei `.join()` auf |
| Schließen | `logger.close()` am Ende, am besten via try-with-resources |
| Backpressure | `.builder().dataSource(ds).maxConcurrency(5).build()` für max. 5 gleichzeitige Logs |
| Error-Callback | `Consumer<AuditLoggingException>` im Konstruktor |
| Executor | Wird von `close()` **nie** shutdown – Verantwortung des Aufrufers |
