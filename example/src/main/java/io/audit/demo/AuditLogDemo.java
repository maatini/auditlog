package io.audit.demo;

import io.audit.core.AuditEntry;
import io.audit.core.AuditLoggingException;
import io.audit.core.PostgresAuditLogger;
import io.audit.core.PostgresAuditLogger.BackpressurePolicy;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Self-contained Demo der Audit-Log-Bibliothek.
 * <p>
 * Startet PostgreSQL via Testcontainers, legt die Tabelle an und demonstriert
 * alle Kernfunktionen. Keine manuelle DB-Installation nötig.
 * <p>
 * Ausführung:
 * <pre>
 * mvn compile test-compile
 * java -cp "$(mvn dependency:build-classpath -q -DincludeScope=test -Dmdep.outputFile=/dev/stdout):target/classes:target/test-classes" io.audit.demo.AuditLogDemo
 * </pre>
 */
public class AuditLogDemo {

    private static final String ANSI_RESET = "\033[0m";
    private static final String ANSI_GREEN = "\033[32m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_CYAN = "\033[36m";
    private static final String ANSI_RED = "\033[31m";
    private static final String ANSI_BOLD = "\033[1m";

    public static void main(String[] args) throws Exception {
        System.out.println(ANSI_BOLD + "\n╔══════════════════════════════════════════════╗");
        System.out.println("║     Audit Log Core — Demo                  ║");
        System.out.println("╚══════════════════════════════════════════════╝\n" + ANSI_RESET);

        // ── Setup: PostgreSQL via Testcontainers ──────────────────────
        System.out.println(ANSI_CYAN + "▶ Starte PostgreSQL (Testcontainers)..." + ANSI_RESET);
        try (var postgres = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("audit_demo")
                .withUsername("demo")
                .withPassword("demo")) {
            postgres.start();
            System.out.println(ANSI_GREEN + "  ✓ PostgreSQL läuft auf " + postgres.getJdbcUrl() + ANSI_RESET);

            // DataSource erstellen
            var dataSource = new PGSimpleDataSource();
            dataSource.setUrl(postgres.getJdbcUrl());
            dataSource.setUser(postgres.getUsername());
            dataSource.setPassword(postgres.getPassword());

            // Tabelle anlegen
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("""
                        CREATE TABLE IF NOT EXISTS audit_log (
                            id          UUID            PRIMARY KEY,
                            timestamp   TIMESTAMPTZ     NOT NULL,
                            actor_id    VARCHAR(255)    NOT NULL,
                            action      VARCHAR(255)    NOT NULL,
                            entity_type VARCHAR(255)    NOT NULL,
                            entity_id   VARCHAR(255)    NOT NULL,
                            changes     JSONB,
                            metadata    JSONB
                        )""");
            }
            System.out.println(ANSI_GREEN + "  ✓ Tabelle 'audit_log' angelegt\n" + ANSI_RESET);

            // ── 1. Basic ──────────────────────────────────────────────
            demoBasic(dataSource);

            // ── 2. Builder ────────────────────────────────────────────
            demoBuilder(dataSource);

            // ── 3. Async ──────────────────────────────────────────────
            demoAsync(dataSource);

            // ── 4. Backpressure ───────────────────────────────────────
            demoBackpressure(dataSource);

            // ── 5. Error Callback ─────────────────────────────────────
            demoErrorCallback(dataSource);

            // ── 6. Batch Import ───────────────────────────────────────
            demoBatch(dataSource);

            // ── 7. Read Back ──────────────────────────────────────────
            demoReadBack(dataSource);
        }

        System.out.println(ANSI_BOLD + ANSI_GREEN + "\n✓ Demo erfolgreich abgeschlossen!" + ANSI_RESET);
    }

    // ─────────────────────────────────────────────────────────────────
    // 1. Basic — einfaches Loggen
    // ─────────────────────────────────────────────────────────────────
    static void demoBasic(DataSource dataSource) {
        header("1. Basic — Einfaches Loggen");
        try (var logger = new PostgresAuditLogger(dataSource)) {
            var entry = AuditEntry.builder()
                    .actorId("demo-user")
                    .action("LOGIN")
                    .entityType("Session")
                    .entityId("sess-001")
                    .metadata(Map.of("ip", "10.0.0.1", "browser", "Chrome"))
                    .build();
            logger.log(entry).join();
            System.out.println("  ✓ Entry logged: " + entry.id());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 2. Builder — alle Optionen
    // ─────────────────────────────────────────────────────────────────
    static void demoBuilder(DataSource dataSource) {
        header("2. Builder — Logger mit voller Konfiguration");
        try (var logger = PostgresAuditLogger.builder()
                .dataSource(dataSource)
                .maxConcurrency(10)
                .backpressurePolicy(BackpressurePolicy.FAST_FAIL)
                .build()) {

            var entry = AuditEntry.builder()
                    .actorId("builder-demo")
                    .action("UPDATE")
                    .entityType("Order")
                    .entityId("ord-042")
                    .changes(Map.of("status", Map.of("old", "PENDING", "new", "SHIPPED")))
                    .build();
            logger.log(entry).join();
            System.out.println("  ✓ Entry logged via Builder-API: " + entry.id());
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 3. Async — Virtual Threads
    // ─────────────────────────────────────────────────────────────────
    static void demoAsync(DataSource dataSource) {
        header("3. Async — Virtual Threads im Hintergrund");
        try (var logger = new PostgresAuditLogger(dataSource)) {
            // Fire-and-Forget: kehrt sofort zurück
            var future = logger.log(AuditEntry.builder()
                    .actorId("async-demo").action("ASYNC").entityType("Test").entityId("async-1")
                    .build());
            System.out.println("  ✓ log() returned sofort (CompletableFuture)");
            future.join(); // warten
            System.out.println("  ✓ Entry persistiert (join() completed)");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 4. Backpressure — FAST_FAIL
    // ─────────────────────────────────────────────────────────────────
    static void demoBackpressure(DataSource dataSource) throws InterruptedException {
        header("4. Backpressure — FAST_FAIL mit max 2 Permits");
        try (var logger = new PostgresAuditLogger(dataSource, 2, BackpressurePolicy.FAST_FAIL)) {
            var success = new AtomicInteger();
            var failed = new AtomicInteger();
            var latch = new CountDownLatch(5);

            for (int i = 0; i < 5; i++) {
                int id = i;
                var entry = AuditEntry.builder()
                        .actorId("bp-demo").action("OP-" + i).entityType("Bulk").entityId("bulk-" + i)
                        .build();
                var future = logger.log(entry);
                future.whenComplete((v, t) -> {
                    if (t != null) failed.incrementAndGet();
                    else success.incrementAndGet();
                    latch.countDown();
                });
            }
            latch.await();
            System.out.println("  ✓ " + success.get() + " erfolgreich, " + failed.get() + " via FAST_FAIL abgelehnt");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 5. Error Callback
    // ─────────────────────────────────────────────────────────────────
    static void demoErrorCallback(DataSource dataSource) {
        header("5. Error Callback — asynchrone Fehlerbenachrichtigung");
        var errors = new ArrayList<AuditLoggingException>();
        try (var logger = new PostgresAuditLogger(
                dataSource, 1, BackpressurePolicy.FAST_FAIL, errors::add)) {

            // Erster Aufruf nutzt das eine Permit
            logger.log(AuditEntry.builder()
                    .actorId("cb-demo").action("OK").entityType("T").entityId("ok-1")
                    .build()).join();

            // Zweiter wird abgelehnt → Callback wird invoked
            var future = logger.log(AuditEntry.builder()
                    .actorId("cb-demo").action("FAIL").entityType("T").entityId("fail-1")
                    .build());
            future.join(); // wirft CompletionException

            System.out.println("  ✓ Error-Callback aufgerufen: " + errors.size() + " Fehler");
            System.out.println("  ✓ Message: " + errors.getFirst().getMessage());
        } catch (Exception ignored) {
            // expected
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 6. Batch — mehrere Entries parallel
    // ─────────────────────────────────────────────────────────────────
    static void demoBatch(DataSource dataSource) {
        header("6. Batch — 10 Entries parallel loggen");
        try (var logger = new PostgresAuditLogger(dataSource)) {
            var futures = new CompletableFuture<?>[10];
            for (int i = 0; i < 10; i++) {
                int id = i;
                var entry = AuditEntry.builder()
                        .actorId("batch-demo")
                        .action("BATCH_WRITE")
                        .entityType("BatchTest")
                        .entityId("batch-" + i)
                        .metadata(Map.of("seq", id, "ts", OffsetDateTime.now().toString()))
                        .build();
                futures[i] = logger.log(entry);
            }
            CompletableFuture.allOf(futures).join();
            System.out.println("  ✓ 10 Entries parallel persistiert");
        }
    }

    // ─────────────────────────────────────────────────────────────────
    // 7. Read Back — gelesene Daten anzeigen
    // ─────────────────────────────────────────────────────────────────
    static void demoReadBack(DataSource dataSource) throws Exception {
        header("7. Read Back — gespeicherte Einträge abfragen");
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT count(*) AS total,
                            count(DISTINCT actor_id) AS actors,
                            count(DISTINCT action) AS actions
                     FROM audit_log""")) {
            rs.next();
            System.out.println("  ✓ " + rs.getInt("total") + " Einträge gesamt");
            System.out.println("  ✓ " + rs.getInt("actors") + " verschiedene Akteure");
            System.out.println("  ✓ " + rs.getInt("actions") + " verschiedene Aktionen");
        }

        // Letzte 3 Einträge anzeigen
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("""
                     SELECT actor_id, action, entity_type, entity_id,
                            LEFT(changes::text, 60) AS changes,
                            LEFT(metadata::text, 60) AS metadata
                     FROM audit_log
                     ORDER BY timestamp DESC
                     LIMIT 3""")) {
            System.out.println();
            while (rs.next()) {
                System.out.println("  ── " + rs.getString("actor_id")
                        + " | " + rs.getString("action")
                        + " | " + rs.getString("entity_type")
                        + " | " + rs.getString("entity_id"));
                String ch = rs.getString("changes");
                if (ch != null && !ch.equals("{}"))
                    System.out.println("     changes:  " + ch);
                String meta = rs.getString("metadata");
                if (meta != null && !meta.equals("{}"))
                    System.out.println("     metadata: " + meta);
            }
        }
    }

    static void header(String title) {
        System.out.println(ANSI_YELLOW + "─── " + title + " " + ANSI_RESET);
    }
}
