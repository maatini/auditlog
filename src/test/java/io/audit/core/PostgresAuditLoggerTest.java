package io.audit.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.mockito.ArgumentCaptor;

@DisplayName("PostgresAuditLogger")
class PostgresAuditLoggerTest {

    private Connection connection;
    private PreparedStatement preparedStatement;
    private HikariDataSource dataSource;
    private Executor syncExecutor = command -> command.run();
    private AuditEntry validEntry;

    @BeforeEach
    void setUp() {
        connection = mock(Connection.class);
        preparedStatement = mock(PreparedStatement.class);
        dataSource = mock(HikariDataSource.class);
        validEntry = AuditEntry.builder()
                .actorId("test-user")
                .action("UPDATE")
                .entityType("Order")
                .entityId("order-1")
                .changes(Map.<String, Object>of("status", Map.of("old", "PENDING", "new", "SHIPPED")))
                .metadata(Map.<String, Object>of("source", "test"))
                .build();
    }

    @Test
    @DisplayName("log inserts entry successfully")
    void log_insertsEntrySuccessfully() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        try (var logger = new PostgresAuditLogger(dataSource, syncExecutor)) {
            logger.log(validEntry).join();
        }

        verify(connection).prepareStatement(contains("INSERT INTO audit_log"));
        verify(preparedStatement).setObject(eq(1), eq(validEntry.id()));
        verify(preparedStatement).setObject(eq(2), eq(validEntry.timestamp()));
        verify(preparedStatement).setString(eq(3), eq("test-user"));
        verify(preparedStatement).setString(eq(4), eq("UPDATE"));
        verify(preparedStatement).setString(eq(5), eq("Order"));
        verify(preparedStatement).setString(eq(6), eq("order-1"));
        verify(preparedStatement).setString(eq(7), contains("status"));
        verify(preparedStatement).setString(eq(8), contains("source"));
        verify(preparedStatement).executeUpdate();
    }

    @Test
    @DisplayName("log inserts entry with empty changes and metadata")
    void log_insertsEntryWithEmptyJson() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        var entry = AuditEntry.builder()
                .actorId("u-1").action("READ").entityType("Doc").entityId("d-1")
                .build();

        try (var logger = new PostgresAuditLogger(dataSource, syncExecutor)) {
            logger.log(entry).join();
        }

        verify(preparedStatement).setString(eq(7), eq("{}"));
        verify(preparedStatement).setString(eq(8), eq("{}"));
        verify(preparedStatement).executeUpdate();
    }

    @Test
    @DisplayName("log throws on null entry")
    void log_throwsOnNullEntry() {
        try (var logger = new PostgresAuditLogger(dataSource, syncExecutor)) {
            assertThrows(NullPointerException.class, () -> logger.log(null));
        }
    }

    @Test
    @DisplayName("log throws AuditLoggingException on SQL error")
    void log_throwsOnSqlException() throws Exception {
        when(dataSource.getConnection()).thenThrow(new SQLException("connection failed"));

        try (var logger = new PostgresAuditLogger(dataSource, syncExecutor)) {
            var future = logger.log(validEntry);
            var ex = assertThrows(Exception.class, future::join);
            assertInstanceOf(AuditLoggingException.class, ex.getCause());
        }
    }

    @Test
    @DisplayName("log wraps exceptions in AuditLoggingException")
    void log_wrapsExceptionInAuditLoggingException() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        doThrow(new SQLException("insert failed")).when(preparedStatement).executeUpdate();

        try (var logger = new PostgresAuditLogger(dataSource, syncExecutor)) {
            var future = logger.log(validEntry);
            var ex = assertThrows(Exception.class, future::join);
            var cause = ex.getCause();
            assertInstanceOf(AuditLoggingException.class, cause);
        }
    }

    @Test
    @DisplayName("close does not close an externally-owned DataSource")
    void close_doesNotCloseExternalDatasource() {
        var logger = new PostgresAuditLogger(dataSource, syncExecutor);
        logger.close();
        verify(dataSource, never()).close();
    }

    @Test
    @DisplayName("close closes an owned DataSource")
    void close_closesOwnedDatasource() {
        var logger = new PostgresAuditLogger(dataSource, syncExecutor, true, false);
        logger.close();
        verify(dataSource).close();
    }

    @Test
    @DisplayName("close shuts down owned ExecutorService")
    void close_shutsDownOwnedExecutorService() {
        var executor = Executors.newSingleThreadExecutor();
        var logger = new PostgresAuditLogger(dataSource, executor, false, true);
        logger.close();
        assertTrue(executor.isShutdown());
    }

    @Test
    @DisplayName("close does not shut down executor from public constructor")
    void close_doesNotShutDownExecutorFromPublicConstructor() {
        var executor = Executors.newSingleThreadExecutor();
        var logger = new PostgresAuditLogger(dataSource, executor);
        logger.close();
        assertFalse(executor.isShutdown(),
                "public PostgresAuditLogger(DataSource, Executor) must not shut down the executor");
        executor.shutdownNow();
    }

    @Test
    @DisplayName("close does not shut down non-owned ExecutorService via package-private constructor")
    void close_doesNotShutDownNonOwnedExecutor() {
        var executor = Executors.newSingleThreadExecutor();
        var logger = new PostgresAuditLogger(dataSource, executor, false, false);
        logger.close();
        assertFalse(executor.isShutdown());
        executor.shutdownNow();
    }

    @Test
    @DisplayName("constructor accepts any DataSource")
    void constructor_acceptsAnyDataSource() {
        var plainDs = mock(DataSource.class);
        assertDoesNotThrow(() -> new PostgresAuditLogger(plainDs, syncExecutor));
    }

    @Test
    @DisplayName("constructor rejects null executor")
    void constructor_rejectsNullExecutor() {
        assertThrows(NullPointerException.class,
                () -> new PostgresAuditLogger(dataSource, (Executor) null));
    }

    @Test
    @DisplayName("constructor with DataSource-only uses virtual thread executor")
    void constructor_datasourceOnly() {
        try (var logger = new PostgresAuditLogger(dataSource)) {
            assertNotNull(logger);
        }
    }

    @Test
    @DisplayName("CompletableFuture completes on successful log")
    void log_completesSuccessfully() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        try (var logger = new PostgresAuditLogger(dataSource, syncExecutor)) {
            var future = logger.log(validEntry);
            assertDoesNotThrow(future::join);
            assertTrue(future.isDone());
            assertFalse(future.isCompletedExceptionally());
        }
    }

    @Test
    @DisplayName("log handles multiple calls")
    void log_handlesMultipleCalls() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        try (var logger = new PostgresAuditLogger(dataSource, syncExecutor)) {
            var entry1 = AuditEntry.builder()
                    .actorId("u-1").action("A1").entityType("T").entityId("1").build();
            var entry2 = AuditEntry.builder()
                    .actorId("u-2").action("A2").entityType("T").entityId("2").build();

            CompletableFuture.allOf(logger.log(entry1), logger.log(entry2)).join();

            verify(preparedStatement, times(2)).executeUpdate();
        }
    }

    @Test
    @DisplayName("handles concurrent log calls without interference")
    void log_handlesConcurrentCalls() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        try (var logger = new PostgresAuditLogger(dataSource)) {
            var futures = new CompletableFuture<?>[20];
            for (int i = 0; i < 20; i++) {
                var entry = AuditEntry.builder()
                        .actorId("u-" + i).action("OP" + i).entityType("T").entityId(String.valueOf(i))
                        .build();
                futures[i] = logger.log(entry);
            }
            assertDoesNotThrow(() -> CompletableFuture.allOf(futures).join());
        }
        verify(preparedStatement, times(20)).executeUpdate();
    }

    @Test
    @DisplayName("logger remains usable after a failed log call")
    void logger_remainsUsableAfterFailedLog() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        doThrow(new SQLException("first write fails"))
                .doReturn(1)
                .when(preparedStatement).executeUpdate();

        try (var logger = new PostgresAuditLogger(dataSource, syncExecutor)) {
            var first = logger.log(validEntry);
            assertThrows(Exception.class, first::join);

            var second = logger.log(validEntry);
            assertDoesNotThrow(second::join);
        }
        verify(preparedStatement, times(2)).executeUpdate();
    }

    @Test
    @DisplayName("throws on JSON circular reference in changes")
    void log_throwsOnJsonCircularReference() {
        var circular = new HashMap<String, Object>();
        circular.put("self", circular);
        var changes = Map.<String, Object>of("nested", circular);
        var entry = AuditEntry.builder()
                .actorId("u-1").action("X").entityType("T").entityId("1")
                .changes(changes)
                .build();

        try (var logger = new PostgresAuditLogger(dataSource, syncExecutor)) {
            var future = logger.log(entry);
            var ex = assertThrows(Exception.class, future::join);
            assertInstanceOf(AuditLoggingException.class, ex.getCause());
        }
    }

    @Test
    @DisplayName("OffsetDateTime in changes is serialized as ISO string, not as timestamp array")
    void log_serializesOffsetDateTimeAsIsoString() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        var entry = AuditEntry.builder()
                .actorId("u-1").action("TEST").entityType("T").entityId("1")
                .changes(Map.of("created_at", OffsetDateTime.now()))
                .build();

        try (var logger = new PostgresAuditLogger(dataSource, syncExecutor)) {
            logger.log(entry).join();
        }

        var captor = ArgumentCaptor.forClass(String.class);
        verify(preparedStatement).setString(eq(7), captor.capture());
        var json = captor.getValue();
        var jsonNode = new ObjectMapper().readTree(json);
        assertTrue(jsonNode.get("created_at").isTextual(),
                "created_at should be an ISO string, got: " + json);
        assertFalse(json.contains("Year"),
                "Should not contain numeric Year field: " + json);
    }

    @Test
    @DisplayName("SQL injection strings are passed as parameter values, not executed")
    void log_withSqlInjectionStrings() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        var entry = AuditEntry.builder()
                .actorId("'; DROP TABLE audit_log; --")
                .action("1; SELECT pg_sleep(10); --")
                .entityType("User'; DELETE FROM users; --")
                .entityId("' OR '1'='1")
                .changes(Map.of("field", "' OR 1=1 --"))
                .metadata(Map.of("script", "<script>alert('xss')</script>"))
                .build();

        try (var logger = new PostgresAuditLogger(dataSource, syncExecutor)) {
            logger.log(entry).join();
        }

        verify(preparedStatement).setString(eq(3), eq("'; DROP TABLE audit_log; --"));
        verify(preparedStatement).setString(eq(4), eq("1; SELECT pg_sleep(10); --"));
        verify(preparedStatement).setString(eq(5), eq("User'; DELETE FROM users; --"));
        verify(preparedStatement).setString(eq(6), eq("' OR '1'='1"));
    }

    @Test
    @DisplayName("constructor with ObjectMapper creates usable logger")
    void constructor_withObjectMapper() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        var mapper = new ObjectMapper();
        try (var logger = new PostgresAuditLogger(dataSource, mapper)) {
            logger.log(validEntry).join();
        }
        verify(preparedStatement).executeUpdate();
    }

    @Test
    @DisplayName("constructor with Executor and ObjectMapper creates usable logger")
    void constructor_withExecutorAndObjectMapper() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        var mapper = new ObjectMapper();
        try (var logger = new PostgresAuditLogger(dataSource, syncExecutor, mapper)) {
            logger.log(validEntry).join();
        }
        verify(preparedStatement).executeUpdate();
    }

    @Test
    @DisplayName("constructor with maxConcurrency creates usable logger")
    void constructor_withMaxConcurrency() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        try (var logger = new PostgresAuditLogger(dataSource, 10)) {
            logger.log(validEntry).join();
        }
        verify(preparedStatement).executeUpdate();
    }

    @Test
    @DisplayName("log releases semaphore on rejected execution")
    void log_releasesSemaphoreOnRejectedExecution() {
        var rejectingExecutor = (Executor) command -> { throw new java.util.concurrent.RejectedExecutionException("rejected"); };
        var semaphore = new Semaphore(1);
        var logger = new PostgresAuditLogger(dataSource, rejectingExecutor, false, false,
                PostgresAuditLogger.OBJECT_MAPPER, semaphore,
                PostgresAuditLogger.BackpressurePolicy.BLOCK, null);

        assertTrue(logger.log(validEntry).isCompletedExceptionally(), "rejected execution should produce failed future");
        assertEquals(1, semaphore.availablePermits(), "semaphore should be released on rejection");
    }

    @Test
    @DisplayName("log returns failed future when interrupted during semaphore acquire")
    void log_interruptedDuringSemaphoreAcquire() {
        var semaphore = new Semaphore(0);
        var logger = new PostgresAuditLogger(dataSource, syncExecutor, false, false,
                PostgresAuditLogger.OBJECT_MAPPER, semaphore,
                PostgresAuditLogger.BackpressurePolicy.BLOCK, null);

        Thread.currentThread().interrupt();
        try {
            var future = logger.log(validEntry);
            assertTrue(future.isCompletedExceptionally());
            var ex = assertThrows(Exception.class, future::join);
            assertInstanceOf(AuditLoggingException.class, ex.getCause());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("close swallows exception from owned DataSource")
    void close_swallowsDataSourceException() throws Exception {
        var ds = mock(HikariDataSource.class);
        doThrow(new RuntimeException("close fail")).when(ds).close();
        var logger = new PostgresAuditLogger(ds, syncExecutor, true, false);
        assertDoesNotThrow(logger::close);
    }

    @Test
    @DisplayName("close shuts down owned ExecutorService that times out")
    void close_shutsDownTimedOutExecutor() throws Exception {
        var executor = mock(java.util.concurrent.ExecutorService.class);
        when(executor.awaitTermination(anyLong(), any())).thenReturn(false);

        var logger = new PostgresAuditLogger(dataSource, executor, false, true);
        logger.close();

        verify(executor).shutdown();
        verify(executor).shutdownNow();
    }

    @Test
    @DisplayName("close handles InterruptedException from ExecutorService")
    void close_handlesInterruptedExecutor() throws Exception {
        var executor = mock(java.util.concurrent.ExecutorService.class);
        when(executor.awaitTermination(anyLong(), any())).thenThrow(new InterruptedException("interrupted"));

        var logger = new PostgresAuditLogger(dataSource, executor, false, true);
        logger.close();

        verify(executor).shutdown();
        verify(executor).shutdownNow();
        assertTrue(Thread.interrupted(), "interrupt flag should be set");
    }

    @Test
    @DisplayName("log with FAST_FAIL policy returns failed future when semaphore exhausted")
    void log_fastFailWhenSemaphoreExhausted() {
        var logger = new PostgresAuditLogger(dataSource, 0, PostgresAuditLogger.BackpressurePolicy.FAST_FAIL);
        var future = logger.log(validEntry);
        assertTrue(future.isCompletedExceptionally());
        var ex = assertThrows(Exception.class, future::join);
        assertInstanceOf(AuditLoggingException.class, ex.getCause());
    }

    @Test
    @DisplayName("BLOCK policy blocks calling thread when semaphore exhausted")
    void log_blocksWhenSemaphoreExhausted() throws Exception {
        var semaphore = new Semaphore(0);
        var logger = new PostgresAuditLogger(dataSource, syncExecutor, false, false,
                PostgresAuditLogger.OBJECT_MAPPER, semaphore,
                PostgresAuditLogger.BackpressurePolicy.BLOCK, null);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        var logResult = CompletableFuture.supplyAsync(() -> {
            logger.log(validEntry).join();
            return "done";
        });

        assertThrows(TimeoutException.class, () -> logResult.get(200, TimeUnit.MILLISECONDS));

        semaphore.release();

        assertEquals("done", logResult.get(5, TimeUnit.SECONDS));
        verify(preparedStatement).executeUpdate();
    }

    @Test
    @DisplayName("log with sufficient permits completes normally")
    void log_withSufficientPermitsCompletes() throws Exception {
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        var logger = new PostgresAuditLogger(dataSource, 10, PostgresAuditLogger.BackpressurePolicy.BLOCK);
        logger.log(validEntry).join();

        verify(preparedStatement).executeUpdate();
    }

    @Test
    @DisplayName("error callback is invoked on insert failure")
    void errorCallback_invokedOnInsertFailure() throws Exception {
        var errors = new ArrayList<AuditLoggingException>();
        var logger = new PostgresAuditLogger(dataSource, syncExecutor, 5,
                PostgresAuditLogger.BackpressurePolicy.BLOCK, errors::add);

        when(dataSource.getConnection()).thenThrow(new SQLException("db fail"));

        assertThrows(Exception.class, () -> logger.log(validEntry).join());
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).getMessage().contains("Database error"));
    }

    @Test
    @DisplayName("error callback is invoked on FAST_FAIL backpressure")
    void errorCallback_invokedOnFastFail() {
        var errors = new ArrayList<AuditLoggingException>();
        var logger = new PostgresAuditLogger(dataSource, 0,
                PostgresAuditLogger.BackpressurePolicy.FAST_FAIL, errors::add);

        logger.log(validEntry);

        assertEquals(1, errors.size());
        assertTrue(errors.get(0).getMessage().contains("Backpressure"));
    }

}
