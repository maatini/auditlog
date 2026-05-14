# Audit Log — Demo für Einsteiger

Diese Demo zeigt dir, wie du mit der Audit-Log-Bibliothek alle Aktionen
in deiner Anwendung aufzeichnest.

## Was ist ein Audit-Log?

Stell dir ein Logbuch vor, in dem jeder wichtige Schritt festgehalten wird:
*Wer* hat *wann* *was* mit *welchen Daten* gemacht? Typische Beispiele:

- Ein Nutzer meldet sich an („Login“)
- Eine Bestellung wechselt von „offen“ auf „versendet“
- Ein Administrator ändert die Rechte eines anderen Nutzers

Jede Aktion wird als Eintrag in PostgreSQL gespeichert und kann später
durchsucht werden.

## Voraussetzungen

- **Java 21** oder neuer
- **Docker** (für PostgreSQL – die Demo startet einen Wegwerf-Container)

Keine manuelle DB-Installation nötig.

## Start

Ein einziger Befehl – Devbox startet Docker-PostgreSQL, baut die Library
und führt alle 7 Demo-Schritte aus:

```bash
devbox run run:demo
```

Nach wenigen Sekunden siehst du eine farbige Ausgabe, die jeden Schritt erklärt:
einfaches Loggen, Builder-API, asynchrone Virtual Threads, Backpressure,
Error-Callbacks und Batch-Import.

## Was passiert?

1. **PostgreSQL starten** – Docker-Container auf Port 5439 (automatisch)
2. **Tabelle anlegen** – `audit_log` mit UUID, JSONB, Timestamps
3. **Sieben Demo-Schritte** – von Basic-Logging bis Read-Back

## Wie nutze ich die Bibliothek im eigenen Projekt?

Siehe [USAGE.md](../USAGE.md) – der ausführliche Leitfaden mit
Builder-Beispielen, Fehlerbehandlung, Best Practices und Quarkus-Integration.
