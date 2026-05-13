# Audit Log Core

Leichtgewichtige Java-Bibliothek für PostgreSQL-basierte Audit-Logs.

**Minimale Abhängigkeiten:** PostgreSQL-Treiber, Jackson, SLF4J. Kein HikariCP-Zwang.

## Maven-Dependency

```xml
<dependency>
    <groupId>io.audit</groupId>
    <artifactId>audit-log-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

## Voraussetzungen

- Java 21+
- PostgreSQL 14+ (mit JSONB-Unterstützung)
- Tabelle via Flyway-Migration anlegen (siehe unten)

## Datenbank-Migration

Das SQL-Migration-Script liegt unter `db/migration/V1__create_audit_log_table.sql`.
Bei manuellem Ausführen:

```bash
psql -U <user> -d <database> -f V1__create_audit_log_table.sql
```

## Nutzung

### Mit vorhandenem Connection-Pool (empfohlen)

```java
// Einen vorhandenen DataSource nutzen (z. B. aus Application-Server / DI-Framework)
PostgresAuditLogger logger = new PostgresAuditLogger(dataSource);

AuditEntry entry = AuditEntry.builder()
    .actorId("user-123")
    .action("UPDATE")
    .entityType("Order")
    .entityId("order-456")
    .changes(Map.of("status", Map.of("old", "PENDING", "new", "SHIPPED")))
    .metadata(Map.of("source_ip", "192.168.1.1"))
    .build();

logger.log(entry);

logger.close();
```

### Mit eigener URL (braucht HikariCP auf dem Classpath)

```java
PostgresAuditLogger logger = PostgresAuditLoggers.create(
    "jdbc:postgresql://localhost:5432/mydb",
    "user",
    "password"
);
```

Dafür HikariCP als Dependency ergänzen:

```xml
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
    <version>5.1.0</version>
</dependency>
```

### Mit Builder (eigener HikariCP-Pool)

```java
PostgresAuditLogger logger = PostgresAuditLoggers.builder()
    .jdbcUrl("jdbc:postgresql://localhost:5432/mydb")
    .username("user")
    .password("pass")
    .maximumPoolSize(10)
    .minimumIdle(2)
    .poolName("my-audit-pool")
    .build();
```

### Backpressure

Die Bibliothek unterstützt zwei Strategien, wenn die Auslastung die konfigurierte Grenze erreicht:

```java
// Blockiert den aufrufenden Thread, bis ein Permit frei wird (Default)
PostgresAuditLogger logger = new PostgresAuditLogger(dataSource, 5);

// Schlägt sofort fehl – gibt eine failed CompletableFuture zurück
PostgresAuditLogger logger = new PostgresAuditLogger(
    dataSource, 5, BackpressurePolicy.FAST_FAIL);
```

| Policy | Verhalten |
|---|---|
| `BLOCK` | Thread wartet (blockierend) auf freies Permit |
| `FAST_FAIL` | `CompletableFuture.failedFuture(AuditLoggingException)` |

### Error-Callback für asynchrone Fehler

Bei `FAST_FAIL` oder SQL-Fehlern kann ein Callback benachrichtigt werden:

```java
PostgresAuditLogger logger = new PostgresAuditLogger(
    dataSource, 5, BackpressurePolicy.FAST_FAIL,
    error -> monitoringService.alert("Audit-Log fehlgeschlagen: " + error.getMessage()));
```

### Eigener Executor

Für benutzerdefinierte Thread-Pools (z. B. begrenzte Parallelität):

```java
var executor = Executors.newFixedThreadPool(10);
var logger = new PostgresAuditLogger(dataSource, executor);
// Der Executor wird von close() NICHT shutdown – liegt in der Verantwortung des Aufrufers
```

### Executor mit Backpressure

```java
var logger = new PostgresAuditLogger(
    dataSource, executor, 10,
    PostgresAuditLogger.BackpressurePolicy.FAST_FAIL,
    error -> log.warn("Audit-Log fehlgeschlagen", error));
```

## Abhängigkeiten im Überblick

| Dependency | Scope | Erforderlich für |
|---|---|---|
| `postgresql` | compile | JDBC-Treiber |
| `jackson-databind` | compile | JSON-Serialisierung |
| `slf4j-api` | compile | Logging |
| `HikariCP` | optional | Nur `PostgresAuditLoggers.create()` |

Die Bibliothek kommt ohne HikariCP aus, wenn du einen eigenen `DataSource` übergibst.

## Schema

| Spalte      | Typ               | Beschreibung                          |
|-------------|-------------------|---------------------------------------|
| id          | UUID              | Primary Key                           |
| timestamp   | TIMESTAMPTZ       | Zeitstempel mit Zeitzone              |
| actor_id    | VARCHAR(255)      | Wer hat die Aktion ausgeführt         |
| action      | VARCHAR(255)      | Aktion (z. B. CREATE, UPDATE, DELETE) |
| entity_type | VARCHAR(255)      | Entitätstyp (z. B. Order, User)      |
| entity_id   | VARCHAR(255)      | ID der Entität                        |
| changes     | JSONB             | Vorher/Nachher-Vergleich              |
| metadata    | JSONB             | Zusätzliche Metadaten                 |

## Architektur

- **Asynchrone Persistierung**: via `CompletableFuture.runAsync()` auf Virtual Threads (Java 21+)
- **Backpressure**: Semaphore-gesteuerte Drosselung (BLOCK / FAST_FAIL) gegen Pool Starvation
- **Connection-Pooling**: wahlweise HikariCP (über Factory) oder eigener Pool
- **JSON-Serialisierung**: Jackson (via `jackson-databind`, ISO-8601-Datumsformate)
- **Fehlerbehandlung**: asynchrone Fehler via `AuditLoggingException` in CompletableFuture + optionalem Callback
- **Minimale Pflichtabhängigkeiten**: PostgreSQL-Treiber, Jackson, SLF4J

## Flyway als optionale Dependency

Wenn Flyway zur Laufzeit genutzt werden soll:

```xml
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
    <version>10.18.0</version>
</dependency>
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-database-postgresql</artifactId>
    <version>10.18.0</version>
</dependency>
```
