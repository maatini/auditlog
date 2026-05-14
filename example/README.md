# Audit Log — Demo für Einsteiger

Diese Demo zeigt dir, wie du mit der Audit-Log-Bibliothek alle Aktionen in deiner Anwendung aufzeichnest — ganz ohne externe Datenbank installieren zu müssen.

## Was ist ein Audit-Log?

Stell dir ein Logbuch vor, in dem jeder wichtige Schritt festgehalten wird: *Wer* hat *wann* *was* mit *welchen Daten* gemacht? Genau das macht ein Audit-Log. Typische Beispiele:

- Ein Nutzer meldet sich an („Login“)
- Eine Bestellung wechselt von „offen“ auf „versendet“
- Ein Administrator ändert die Rechte eines anderen Nutzers

Jede dieser Aktionen wird als Eintrag in der Datenbank gespeichert und kann später durchsucht werden.

## Voraussetzungen

Du brauchst nur zwei Dinge:

- **Java 21** oder neuer
- **Docker** (wird von Testcontainers automatisch genutzt, um eine PostgreSQL-Datenbank zu starten)

Du musst keine Datenbank installieren oder Tabellen anlegen — das erledigt die Demo für dich.

## So startest du die Demo

Zuerst die Bibliothek bauen und in dein lokales Maven-Repository installieren:

```bash
# Im Hauptverzeichnis des Projekts:
mvn install -DskipTests
```

Dann die Demo ausführen:

```bash
# Aus dem example-Verzeichnis:
cd example
mvn compile exec:java -Dexec.mainClass=io.audit.demo.AuditLogDemo
```

Nach wenigen Sekunden siehst du eine farbige Ausgabe, die jeden Schritt erklärt.

## Was passiert beim Start?

Die Demo läuft in drei Phasen ab:

### 1. Datenbank starten (automatisch)

Die Demo nutzt **Testcontainers**. Das ist eine Bibliothek, die für die Dauer des Programms eine echte PostgreSQL-Datenbank in einem Docker-Container startet. Wenn das Programm endet, wird der Container automatisch gelöscht — nichts bleibt zurück.

### 2. Tabelle anlegen

Die Demo erzeugt die Tabelle `audit_log` mit folgender Struktur:

| Spalte       | Typ           | Bedeutung                              |
|-------------|---------------|----------------------------------------|
| `id`        | `UUID`        | Eindeutige Kennung des Eintrags        |
| `timestamp` | `TIMESTAMPTZ` | Zeitpunkt der Aktion (mit Zeitzone)    |
| `actor_id`  | `VARCHAR`     | Wer hat gehandelt? (z. B. Nutzername)  |
| `action`    | `VARCHAR`     | Was wurde gemacht? (z. B. „UPDATE“)    |
| `entity_type` | `VARCHAR`  | Um welche Art von Objekt geht es?      |
| `entity_id` | `VARCHAR`     | Welches konkrete Objekt? (z. B. ID)    |
| `changes`   | `JSONB`       | Was hat sich geändert? (alte/neue Werte) |
| `metadata`  | `JSONB`       | Zusätzliche Infos (IP-Adresse, Browser, …) |

Die Spalten `changes` und `metadata` sind JSONB — das erlaubt flexible, strukturierte Daten ohne fixes Schema.

### 3. Sieben Demo-Schritte durchlaufen

Jeder Schritt zeigt eine andere Fähigkeit der Bibliothek.

---

## Schritt 1: Einfaches Loggen

```java
var entry = AuditEntry.builder()
        .actorId("demo-user")
        .action("LOGIN")
        .entityType("Session")
        .entityId("sess-001")
        .metadata(Map.of("ip", "10.0.0.1", "browser", "Chrome"))
        .build();
logger.log(entry).join();
```

**Was passiert hier?** Du baust einen Audit-Eintrag mit einem **Builder** (einem Hilfsobjekt, das Schritt für Schritt befüllt wird). Mit `.build()` wird der Eintrag fertiggestellt. `logger.log(entry)` schreibt ihn in die Datenbank und gibt ein `CompletableFuture` zurück — ein „Versprechen“, dass die Arbeit erledigt wird. `.join()` wartet, bis das Versprechen eingelöst ist.

Das `metadata`-Feld nimmt beliebige Schlüssel-Wert-Paare auf — hier zum Beispiel die IP-Adresse und den Browser des Nutzers.

---

## Schritt 2: Logger mit Builder konfigurieren

Nicht nur Audit-Einträge, auch der **Logger selbst** wird über einen Builder erstellt:

```java
var logger = PostgresAuditLogger.builder()
        .dataSource(dataSource)
        .maxConcurrency(10)
        .backpressurePolicy(BackpressurePolicy.FAST_FAIL)
        .build();
```

| Einstellung          | Bedeutung                                                                 |
|----------------------|---------------------------------------------------------------------------|
| `dataSource`         | Die Datenbank-Verbindung (hier unsere PostgreSQL)                          |
| `maxConcurrency`     | Wie viele Einträge dürfen maximal gleichzeitig geschrieben werden? (hier: 10) |
| `backpressurePolicy` | Was passiert, wenn das Limit erreicht ist? (hier: sofort ablehnen)          |

Dieser Schritt zeigt auch das `changes`-Feld — hier wird dokumentiert, dass eine Bestellung von „PENDING“ auf „SHIPPED“ wechselt:

```java
.changes(Map.of("status", Map.of("old", "PENDING", "new", "SHIPPED")))
```

---

## Schritt 3: Asynchrone Verarbeitung

```java
var future = logger.log(entry);
System.out.println("✓ log() sofort zurück");
future.join();
System.out.println("✓ Jetzt ist der Eintrag in der DB");
```

**Das ist der Kern der Bibliothek.** `logger.log()` gibt sofort die Kontrolle an dein Programm zurück — das Schreiben in die Datenbank passiert im Hintergrund auf einem **Virtual Thread**. Dein Programm blockiert nicht, während die Datenbank arbeitet.

> **Virtual Threads** sind ein Feature von Java 21. Sie sind extrem leichtgewichtig — du kannst Zehntausende davon starten, ohne dass dein Programm langsamer wird.

---

## Schritt 4: Backpressure — Schutz vor Überlastung

Was passiert, wenn mehr Anfragen kommen, als der Logger verarbeiten kann? Dafür gibt es **Backpressure**.

```java
var logger = new PostgresAuditLogger(dataSource, 2, BackpressurePolicy.FAST_FAIL);
```

Hier sagen wir: maximal **2** gleichzeitige Schreibvorgänge. Die Demo schickt dann **5** Einträge auf einmal los:

- 2 werden sofort angenommen (die „Permits“ sind frei)
- 3 werden abgelehnt, weil kein Permit mehr verfügbar ist

Das Ergebnis: `2 erfolgreich, 3 via FAST_FAIL abgelehnt`.

> **FAST_FAIL** bedeutet: „Wenn kein Platz ist, lehne sofort ab.“ Das ist die richtige Wahl, wenn du lieber einen Fehler bekommst, als dass deine Anwendung langsamer wird.

---

## Schritt 5: Error Callback — Fehler mitbekommen

Weil alles im Hintergrund läuft, bekommst du Fehler nicht direkt mit. Dafür gibt es den **Error Callback**:

```java
var errors = new ArrayList<AuditLoggingException>();
var logger = new PostgresAuditLogger(
        dataSource, 1, BackpressurePolicy.FAST_FAIL, errors::add);
```

Das vierte Argument ist eine Funktion, die bei jedem Fehler aufgerufen wird. Hier sammeln wir alle Fehler in einer Liste. In einer echten Anwendung würdest du hier zum Beispiel einen Alarm auslösen oder einen Zähler erhöhen.

Die Demo erzeugt absichtlich einen Fehler (zwei Einträge, aber nur ein Permit) und zeigt dann den Inhalt der Fehlerliste an.

---

## Schritt 6: Batch — viele Einträge auf einmal

```java
var futures = new CompletableFuture<?>[10];
for (int i = 0; i < 10; i++) {
    futures[i] = logger.log(entry);
}
CompletableFuture.allOf(futures).join();
```

Hier werden **10 Einträge parallel** in die Datenbank geschrieben. `CompletableFuture.allOf()` fasst alle Versprechen zusammen, und `.join()` wartet, bis wirklich alle fertig sind.

Das zeigt: Die Bibliothek skaliert gut — du kannst bedenkenlos hunderte Einträge parallel loggen.

---

## Schritt 7: Read Back — Daten prüfen

Zum Abschluss liest die Demo die gespeicherten Einträge direkt aus der Datenbank und zeigt:

- Wie viele Einträge insgesamt geschrieben wurden
- Wie viele verschiedene Akteure und Aktionen es gab
- Die letzten drei Einträge mit ihren Details

So siehst du, dass alle vorherigen Schritte tatsächlich in der Datenbank angekommen sind.

---

## Zusammenfassung

| Konzept             | Kurz erklärt                                                   |
|---------------------|----------------------------------------------------------------|
| `AuditEntry`        | Ein einzelner Audit-Eintrag (wer, was, wann, welche Daten)     |
| `AuditEntry.builder()` | Hilfsobjekt zum bequemen Erstellen von Einträgen            |
| `PostgresAuditLogger` | Der Logger — schreibt Einträge in PostgreSQL                |
| `logger.log()`      | Gibt ein `CompletableFuture` zurück — blockiert dein Programm nicht |
| `Backpressure`      | Begrenzt die Anzahl gleichzeitiger Schreibvorgänge             |
| `FAST_FAIL`         | Lehnt neue Einträge ab, wenn das Limit erreicht ist            |
| `Error Callback`    | Deine Funktion, die bei Fehlern aufgerufen wird                |
| Virtual Threads     | Java-21-Feature für extrem leichtgewichtige Hintergrundarbeit  |

## Nächste Schritte

Wenn du die Bibliothek in deinem eigenen Projekt nutzen willst:

1. Füge `audit-log-core` als Dependency hinzu
2. Erstelle einen `PostgresAuditLogger` mit deiner DataSource
3. Rufe `logger.log(entry)` auf, wann immer du eine Aktion aufzeichnen willst
4. In Produktion: nutze eine echte PostgreSQL-Datenbank mit Connection-Pool (z. B. HikariCP)
