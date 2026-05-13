package io.audit.core;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@Tag("integration")
@DisplayName("PostgresAuditLogger (Integration)")
class PostgresAuditLoggerIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("audit_test")
            .withUsername("test")
            .withPassword("test");

    private static HikariDataSource dataSource;
    private PostgresAuditLogger logger;

    @BeforeAll
    static void setUpDatabase() {
        var config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(5);
        dataSource = new HikariDataSource(config);

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
                    )
                    """);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create table", e);
        }
    }

    @AfterAll
    static void tearDownDatabase() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    @BeforeEach
    void setUp() {
        logger = new PostgresAuditLogger(dataSource);
    }

    @AfterEach
    void cleanUp() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("DELETE FROM audit_log");
        }
        logger.close();
    }

    @Test
    @DisplayName("persists a complete audit entry")
    void persistsCompleteEntry() throws Exception {
        var entry = AuditEntry.builder()
                .actorId("user-42")
                .action("UPDATE")
                .entityType("Order")
                .entityId("ord-123")
                .changes(Map.of(
                        "status", Map.of("old", "PENDING", "new", "SHIPPED"),
                        "total", Map.of("old", 99.0, "new", 129.0)
                ))
                .metadata(Map.of(
                        "source_ip", "10.0.0.1",
                        "correlation_id", "corr-abc"
                ))
                .build();

        logger.log(entry).join();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM audit_log")) {

            assertTrue(rs.next());
            assertEquals(entry.id(), rs.getObject("id", UUID.class));
            assertEquals(entry.actorId(), rs.getString("actor_id"));
            assertEquals(entry.action(), rs.getString("action"));
            assertEquals(entry.entityType(), rs.getString("entity_type"));
            assertEquals(entry.entityId(), rs.getString("entity_id"));
            assertTrue(rs.getTimestamp("timestamp").toInstant().getEpochSecond() > 0);
            assertNotNull(rs.getString("changes"));
            assertNotNull(rs.getString("metadata"));
            assertFalse(rs.next(), "should be exactly one row");
        }
    }

    @Test
    @DisplayName("persists entry with empty JSONB fields")
    void persistsEmptyJsonb() throws Exception {
        var entry = AuditEntry.builder()
                .actorId("system")
                .action("PING")
                .entityType("Health")
                .entityId("check-1")
                .build();

        logger.log(entry).join();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT changes, metadata FROM audit_log")) {

            assertTrue(rs.next());
            assertEquals("{}", rs.getString("changes"));
            assertEquals("{}", rs.getString("metadata"));
        }
    }

    @Test
    @DisplayName("persists multiple entries")
    void persistsMultipleEntries() throws Exception {
        var e1 = AuditEntry.builder().actorId("u1").action("A").entityType("T").entityId("1").build();
        var e2 = AuditEntry.builder().actorId("u2").action("B").entityType("T").entityId("2").build();

        logger.log(e1).join();
        logger.log(e2).join();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT count(*) AS cnt FROM audit_log")) {
            rs.next();
            assertEquals(2, rs.getInt("cnt"));
        }
    }

    @Test
    @DisplayName("persists Unicode and special characters correctly")
    void persistsEntryWithUnicode() throws Exception {
        var entry = AuditEntry.builder()
                .actorId("éàüöñ")              // éàüöñ
                .action("✔️")                                  // ✔️
                .entityType("Geschäft")                             // Geschäft
                .entityId("emoji-🚀-👍")            // 🚀👍
                .changes(Map.of("note", "Valor: €1.000,--"))       // Valor: €1.000,--
                .metadata(Map.of("source", "中文/日本語")) // 中文/日本語
                .build();

        logger.log(entry).join();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM audit_log")) {

            assertTrue(rs.next());
            assertEquals("éàüöñ", rs.getString("actor_id"));
            assertEquals("✔️", rs.getString("action"));
            assertEquals("Geschäft", rs.getString("entity_type"));
            assertEquals("emoji-🚀-👍", rs.getString("entity_id"));
            String changesJson = rs.getString("changes");
            assertTrue(changesJson.contains("€"), "JSON should contain euro sign");
        }
    }

    @Test
    @DisplayName("persists large JSON payload (>100KB)")
    void persistsEntryWithLargePayload() throws Exception {
        var largeMap = new java.util.HashMap<String, Object>();
        for (int i = 0; i < 500; i++) {
            largeMap.put("key-" + i, "value-" + "x".repeat(200));
        }
        var entry = AuditEntry.builder()
                .actorId("bulk-loader")
                .action("IMPORT")
                .entityType("LargeDoc")
                .entityId("doc-1")
                .changes(largeMap)
                .build();

        logger.log(entry).join();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT changes FROM audit_log")) {

            assertTrue(rs.next());
            var stored = rs.getString("changes");
            assertTrue(stored.length() > 100_000, "JSON should exceed 100KB, got " + stored.length());
            assertTrue(stored.contains("key-499"), "JSON should contain last key");
        }
    }

    @Test
    @DisplayName("handles 10 concurrent writers with 100 entries each")
    void handlesConcurrentWrites() throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        int threadCount = 10;
        int entriesPerThread = 100;

        var futures = new CompletableFuture<?>[threadCount];
        for (int t = 0; t < threadCount; t++) {
            int threadId = t;
            futures[t] = CompletableFuture.runAsync(() -> {
                var localLogger = new PostgresAuditLogger(dataSource);
                try {
                    for (int i = 0; i < entriesPerThread; i++) {
                        var entry = AuditEntry.builder()
                                .actorId("concurrent-user")
                                .action("WRITE")
                                .entityType("ConcurrentTest")
                                .entityId("t" + threadId + "-e" + i)
                                .metadata(Map.of("thread", threadId, "seq", i))
                                .build();
                        localLogger.log(entry).join();
                    }
                } finally {
                    localLogger.close();
                }
            }, executor);
        }

        CompletableFuture.allOf(futures).join();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                     "SELECT count(*) AS cnt, count(DISTINCT entity_id) AS distinct_cnt FROM audit_log")) {
            rs.next();
            int total = rs.getInt("cnt");
            int distinct = rs.getInt("distinct_cnt");
            assertEquals(threadCount * entriesPerThread, total, "all entries should be persisted");
            assertEquals(threadCount * entriesPerThread, distinct,
                    "all entity_ids should be unique (no duplicates)");
        }
    }

    @Test
    @DisplayName("SQL injection strings are stored as literal values, not interpreted")
    void persistsSqlInjectionStringsAsLiteral() throws Exception {
        var entry = AuditEntry.builder()
                .actorId("'; DROP TABLE audit_log; --")
                .action("1; SELECT pg_sleep(10); --")
                .entityType("User'; DELETE FROM users; --")
                .entityId("' OR '1'='1")
                .changes(Map.of("field", "' OR 1=1 --"))
                .build();

        logger.log(entry).join();

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM audit_log WHERE actor_id LIKE '%DROP%'")) {

            assertTrue(rs.next(), "entry with injection pattern should be stored");
            assertEquals("'; DROP TABLE audit_log; --", rs.getString("actor_id"));
            assertEquals("1; SELECT pg_sleep(10); --", rs.getString("action"));

            // Verify table still exists (injection had no effect)
            try (var checkStmt = conn.createStatement();
                 var checkRs = checkStmt.executeQuery("SELECT count(*) FROM audit_log")) {
                checkRs.next();
                assertTrue(checkRs.getInt(1) >= 1, "audit_log table should be intact");
            }
        }
    }

    @Test
    @DisplayName("Flyway migration runs without error")
    void validatesFlywayMigration() {
        var flyway = org.flywaydb.core.Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();
        assertDoesNotThrow(flyway::migrate, "Flyway migration V1 should execute successfully");
    }

    @Test
    @DisplayName("String values exceeding VARCHAR(255) are rejected by the database")
    void rejectsOverlyLongStrings() {
        var longStr = "x".repeat(300);
        var entry = AuditEntry.builder()
                .actorId(longStr)
                .action("READ")
                .entityType("User")
                .entityId("1")
                .build();

        var future = logger.log(entry);
        var ex = assertThrows(Exception.class, future::join);
        assertInstanceOf(AuditLoggingException.class, ex.getCause());
    }
}
