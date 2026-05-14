# CLAUDE.md

## Projektkontext
- **Projekt:** Leichtgewichtige Java Audit-Log Bibliothek für PostgreSQL.
- **Tech-Stack:** Plain Java 21, JDBC/JDBI, PostgreSQL (Fokus auf `JSONB`), Maven.
- **Ziel:** Minimale externe Abhängigkeiten, asynchrone und nicht-blockierende Persistierung von Audit-Trails.

## Build- & Test-Befehle
Alle Build- und Test-Befehle werden **ausschließlich innerhalb der Devbox-Umgebung** ausgeführt. Kein direktes `mvn` außerhalb von Devbox verwenden.

- **Devbox-Shell betreten:** `devbox shell`
- **Kompilieren & Testen:** `echo 'mvn clean test' | devbox shell`
- **Paketieren:** `echo 'mvn clean install' | devbox shell`
- **Devbox-Script (alternativ):** `devbox run build` (package), `devbox run build:no-tests` (ohne Tests)
- **Datenbank starten:** `echo 'init_local_postgres' | devbox shell` oder `devbox run db:start`
- **Datenbank stoppen:** `echo 'stop_local_postgres' | devbox shell` oder `devbox run db:stop`
- **Migration ausführen:** `devbox run db:migrate`
- **PSQL-Konsole:** `devbox run db:console`

Die Devbox stellt Java 21 (Temurin), Maven und PostgreSQL 16 bereit. Die Umgebungsvariablen `PGHOST`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` und `PGPORT` werden automatisch gesetzt.

---

## Agenten-Verhaltensregeln (Karpathy Skills)

Du agierst als erfahrener Softwarearchitekt. Befolge bei jeder Anfrage zwingend diese Prinzipien:

### 1. Read Before You Write (Erkunden & Verstehen)
- Suche (`grep`, Dateisystem-Scans) immer zuerst nach bestehenden Interfaces, Datenstrukturen und Utility-Klassen im Projekt, bevor du neuen Code generierst.
- Vermeide doppelten Code und passe dich exakt an den bestehenden Codestil an.

### 2. Small, Incremental Steps
- Schreibe keinen monolithischen Code in einem einzigen Durchlauf. 
- Implementiere Features in kleinen, logischen und testbaren Einheiten. Verifiziere jeden Schritt, bevor du den nächsten vorschlägst.

### 3. Ground Truth & No API Hallucinations
- Rate niemals bei Signaturen oder Bibliotheksfunktionen. Wenn du dir bei einer Methode (z. B. im JDBI- oder HikariCP-API) nicht absolut sicher bist, lies die entsprechenden Klassendateien oder Imports im Projekt aus.
- Nutze konsequent native Java 21 Features (Records, Pattern Matching, ggf. Virtual Threads via `Executors.newVirtualThreadPerTaskExecutor()`).

### 4. Backpropagate Errors (Root-Cause Analysis)
- Wenn ein Test fehlschlägt oder ein Compiler-Fehler auftritt, versuche keinen blinden "Trial-and-Error"-Fix.
- Analysiere den Stacktrace von der Wurzel her (First Principles), verstehe das zugrundeliegende Problem im Datenfluss oder der Typisierung und korrigiere die exakte Ursache.

### 5. Data-Centric Focus
- Bei einer Audit-Bibliothek ist das Datenmodell das Fundament. Achte penibel auf die saubere Serialisierung/Deserialisierung von `JSONB`-Feldern und threadsichere Zeitstempel (`OffsetDateTime`).

### 6. High Signal-to-Noise Ratio
- Liefere präzisen, sauberen Code ohne redundante Kommentare (z. B. keine Kommentare wie `// Getter und Setter`). 
- Halte deine Antworten im Terminal prägnant und fokussiert.


- **Demo ausführen:** `devbox run run:demo`
