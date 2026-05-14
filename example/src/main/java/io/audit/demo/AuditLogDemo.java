package io.audit.demo;

import io.audit.core.AuditEntry;
import io.audit.core.AuditLoggingException;
import io.audit.core.PostgresAuditLogger;
import io.audit.core.PostgresAuditLogger.BackpressurePolicy;
import org.postgresql.ds.PGSimpleDataSource;

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

public class AuditLogDemo {

    private static final String ANSI_RESET = "\033[0m";
    private static final String ANSI_GREEN = "\033[32m";
    private static final String ANSI_YELLOW = "\033[33m";
    private static final String ANSI_CYAN = "\033[36m";
    private static final String ANSI_RED = "\033[31m";
    private static final String ANSI_BOLD = "\033[1m";

    private static DataSource createDataSource() {
        var ds = new PGSimpleDataSource();
        ds.setServerNames(new String[]{"localhost"});
        ds.setPortNumbers(new int[]{5439});
        ds.setDatabaseName("audit_demo");
        ds.setUser("demo");
        ds.setPassword("demo");
        return ds;
    }

    public static void main(String[] args) throws Exception {
        System.out.println(ANSI_BOLD + "\n\u2554\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2557");
        System.out.println("\u2551     Audit Log Core \u2014 Demo                  \u2551");
        System.out.println("\u255a\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u255d\n" + ANSI_RESET);

        System.out.println(ANSI_CYAN + "\u25b6 Verbinde zu PostgreSQL (localhost:5439)..." + ANSI_RESET);
        var dataSource = createDataSource();

        try (Connection conn = dataSource.getConnection()) {
            System.out.println(ANSI_GREEN + "  \u2713 Verbunden mit PostgreSQL" + ANSI_RESET);
        }

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS audit_log (id UUID PRIMARY KEY, timestamp TIMESTAMPTZ NOT NULL, actor_id VARCHAR(255) NOT NULL, action VARCHAR(255) NOT NULL, entity_type VARCHAR(255) NOT NULL, entity_id VARCHAR(255) NOT NULL, changes JSONB, metadata JSONB)");
        }
        System.out.println(ANSI_GREEN + "  \u2713 Tabelle 'audit_log' angelegt\n" + ANSI_RESET);

        demoBasic(dataSource);
        demoBuilder(dataSource);
        demoAsync(dataSource);
        demoBackpressure(dataSource);
        demoErrorCallback(dataSource);
        demoBatch(dataSource);
        demoReadBack(dataSource);

        System.out.println(ANSI_BOLD + ANSI_GREEN + "\n\u2713 Demo erfolgreich abgeschlossen!" + ANSI_RESET);
    }

    static void demoBasic(DataSource dataSource) {
        header("1. Basic \u2014 Einfaches Loggen");
        try (var logger = new PostgresAuditLogger(dataSource)) {
            var entry = AuditEntry.builder()
                    .actorId("demo-user").action("LOGIN").entityType("Session").entityId("sess-001")
                    .metadata(Map.of("ip", "10.0.0.1", "browser", "Chrome")).build();
            logger.log(entry).join();
            System.out.println("  \u2713 Entry logged: " + entry.id());
        }
    }

    static void demoBuilder(DataSource dataSource) {
        header("2. Builder \u2014 Logger mit voller Konfiguration");
        try (var logger = PostgresAuditLogger.builder().dataSource(dataSource).maxConcurrency(10).backpressurePolicy(BackpressurePolicy.FAST_FAIL).build()) {
            var entry = AuditEntry.builder()
                    .actorId("builder-demo").action("UPDATE").entityType("Order").entityId("ord-042")
                    .changes(Map.of("status", Map.of("old", "PENDING", "new", "SHIPPED"))).build();
            logger.log(entry).join();
            System.out.println("  \u2713 Entry logged via Builder-API: " + entry.id());
        }
    }

    static void demoAsync(DataSource dataSource) {
        header("3. Async \u2014 Virtual Threads im Hintergrund");
        try (var logger = new PostgresAuditLogger(dataSource)) {
            var future = logger.log(AuditEntry.builder()
                    .actorId("async-demo").action("ASYNC").entityType("Test").entityId("async-1").build());
            System.out.println("  \u2713 log() returned sofort (CompletableFuture)");
            future.join();
            System.out.println("  \u2713 Entry persistiert (join() completed)");
        }
    }

    static void demoBackpressure(DataSource dataSource) throws InterruptedException {
        header("4. Backpressure \u2014 FAST_FAIL mit max 2 Permits");
        try (var logger = new PostgresAuditLogger(dataSource, 2, BackpressurePolicy.FAST_FAIL)) {
            var success = new AtomicInteger();
            var failed = new AtomicInteger();
            var latch = new CountDownLatch(5);
            for (int i = 0; i < 5; i++) {
                var entry = AuditEntry.builder()
                        .actorId("bp-demo").action("OP-" + i).entityType("Bulk").entityId("bulk-" + i).build();
                logger.log(entry).whenComplete((v, t) -> {
                    if (t != null) failed.incrementAndGet();
                    else success.incrementAndGet();
                    latch.countDown();
                });
            }
            latch.await();
            System.out.println("  \u2713 " + success.get() + " erfolgreich, " + failed.get() + " via FAST_FAIL abgelehnt");
        }
    }

    static void demoErrorCallback(DataSource dataSource) {
        header("5. Error Callback \u2014 asynchrone Fehlerbenachrichtigung");
        var errors = new ArrayList<AuditLoggingException>();
        try (var logger = new PostgresAuditLogger(dataSource, 1, BackpressurePolicy.FAST_FAIL, errors::add)) {
            logger.log(AuditEntry.builder()
                    .actorId("cb-demo").action("OK").entityType("T").entityId("ok-1").build()).join();
            logger.log(AuditEntry.builder()
                    .actorId("cb-demo").action("FAIL").entityType("T").entityId("fail-1").build()).join();
            System.out.println("  \u2713 Error-Callback aufgerufen: " + errors.size() + " Fehler");
            System.out.println("  \u2713 Message: " + errors.getFirst().getMessage());
        } catch (Exception ignored) {}
    }

    static void demoBatch(DataSource dataSource) {
        header("6. Batch \u2014 10 Entries parallel loggen");
        try (var logger = new PostgresAuditLogger(dataSource)) {
            var futures = new CompletableFuture<?>[10];
            for (int i = 0; i < 10; i++) {
                futures[i] = logger.log(AuditEntry.builder()
                        .actorId("batch-demo").action("BATCH_WRITE").entityType("BatchTest").entityId("batch-" + i)
                        .metadata(Map.of("seq", i, "ts", OffsetDateTime.now().toString())).build());
            }
            CompletableFuture.allOf(futures).join();
            System.out.println("  \u2713 10 Entries parallel persistiert");
        }
    }

    static void demoReadBack(DataSource dataSource) throws Exception {
        header("7. Read Back \u2014 gespeicherte Eintr\u00e4ge abfragen");
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) AS total, count(DISTINCT actor_id) AS actors, count(DISTINCT action) AS actions FROM audit_log")) {
            rs.next();
            System.out.println("  \u2713 " + rs.getInt("total") + " Eintr\u00e4ge gesamt");
            System.out.println("  \u2713 " + rs.getInt("actors") + " verschiedene Akteure");
            System.out.println("  \u2713 " + rs.getInt("actions") + " verschiedene Aktionen");
        }
        try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT actor_id, action, entity_type, entity_id, LEFT(changes::text, 60) AS changes, LEFT(metadata::text, 60) AS metadata FROM audit_log ORDER BY timestamp DESC LIMIT 3")) {
            System.out.println();
            while (rs.next()) {
                System.out.println("  \u2500\u2500 " + rs.getString("actor_id") + " | " + rs.getString("action") + " | " + rs.getString("entity_type") + " | " + rs.getString("entity_id"));
                String ch = rs.getString("changes");
                if (ch != null && !ch.equals("{}")) System.out.println("     changes:  " + ch);
                String meta = rs.getString("metadata");
                if (meta != null && !meta.equals("{}")) System.out.println("     metadata: " + meta);
            }
        }
    }

    static void header(String title) {
        System.out.println(ANSI_YELLOW + "\u2500\u2500\u2500 " + title + " " + ANSI_RESET);
    }
}
