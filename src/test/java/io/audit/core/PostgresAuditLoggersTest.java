package io.audit.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("PostgresAuditLoggers")
class PostgresAuditLoggersTest {

    @Test
    @DisplayName("create constructs logger with JDBC URL")
    void create_withJdbcUrl() {
        var logger = PostgresAuditLoggers.create(
                "jdbc:postgresql://localhost:1/test", "u", "p");
        assertNotNull(logger);
        logger.close();
    }

    @Test
    @DisplayName("create constructs logger with JDBC URL and executor")
    void create_withJdbcUrlAndExecutor() {
        var executor = (Executor) task -> task.run();
        var logger = PostgresAuditLoggers.create(
                "jdbc:postgresql://localhost:1/test", "u", "p", executor);
        assertNotNull(logger);
        logger.close();
    }

    @Test
    @DisplayName("builder creates logger with default settings")
    void builder_defaultSettings() {
        var logger = PostgresAuditLoggers.builder()
                .jdbcUrl("jdbc:postgresql://localhost:1/test")
                .username("u")
                .password("p")
                .build();
        assertNotNull(logger);
        logger.close();
    }

    @Test
    @DisplayName("builder creates logger with custom pool settings")
    void builder_customPoolSettings() {
        var logger = PostgresAuditLoggers.builder()
                .jdbcUrl("jdbc:postgresql://localhost:1/test")
                .username("u")
                .password("p")
                .maximumPoolSize(10)
                .minimumIdle(2)
                .poolName("custom-pool")
                .build();
        assertNotNull(logger);
        logger.close();
    }

    @Test
    @DisplayName("builder creates logger with custom executor — does not shut it down on close")
    void builder_customExecutor() {
        var executor = Executors.newSingleThreadExecutor();
        var logger = PostgresAuditLoggers.builder()
                .jdbcUrl("jdbc:postgresql://localhost:1/test")
                .username("u")
                .password("p")
                .executor(executor)
                .build();
        assertNotNull(logger);
        logger.close();
        assertFalse(executor.isShutdown(), "user-provided executor must not be shut down");
        executor.shutdownNow();
    }

    @Test
    @DisplayName("builder creates logger with custom ObjectMapper")
    void builder_customObjectMapper() {
        var logger = PostgresAuditLoggers.builder()
                .jdbcUrl("jdbc:postgresql://localhost:1/test")
                .username("u")
                .password("p")
                .objectMapper(new com.fasterxml.jackson.databind.ObjectMapper())
                .build();
        assertNotNull(logger);
        logger.close();
    }

    @Test
    @DisplayName("builder creates logger with backpressure")
    void builder_backpressure() {
        var logger = PostgresAuditLoggers.builder()
                .jdbcUrl("jdbc:postgresql://localhost:1/test")
                .username("u")
                .password("p")
                .maxConcurrency(5)
                .backpressurePolicy(PostgresAuditLogger.BackpressurePolicy.FAST_FAIL)
                .build();
        assertNotNull(logger);
        logger.close();
    }

    @Test
    @DisplayName("builder creates logger with error callback")
    void builder_errorCallback() {
        var logger = PostgresAuditLoggers.builder()
                .jdbcUrl("jdbc:postgresql://localhost:1/test")
                .username("u")
                .password("p")
                .errorCallback(e -> {})
                .build();
        assertNotNull(logger);
        logger.close();
    }

    @Test
    @DisplayName("builder throws on missing jdbcUrl")
    void builder_missingJdbcUrl() {
        assertThrows(NullPointerException.class,
                () -> PostgresAuditLoggers.builder().username("u").password("p").build());
    }

    @Test
    @DisplayName("builder throws on missing username")
    void builder_missingUsername() {
        assertThrows(NullPointerException.class,
                () -> PostgresAuditLoggers.builder().jdbcUrl("jdbc:postgresql://localhost:1/test").password("p").build());
    }

    @Test
    @DisplayName("builder throws on missing password")
    void builder_missingPassword() {
        assertThrows(NullPointerException.class,
                () -> PostgresAuditLoggers.builder().jdbcUrl("jdbc:postgresql://localhost:1/test").username("u").build());
    }
}
