package io.audit.core;

import com.zaxxer.hikari.HikariDataSource;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * JMH Performance Benchmarks für Audit Log Core.
 *
 * <p>Ausführung:
 * <pre>mvn test-compile exec:java -Dexec.mainClass=io.audit.core.AuditLoggerBenchmark</pre>
 * Oder via JMH Uber-JAR:
 * <pre>mvn package &amp;&amp; java -jar target/benchmarks.jar</pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 2)
@Fork(1)
public class AuditLoggerBenchmark {

    private DataSource dataSource;
    private Connection connection;
    private PreparedStatement preparedStatement;
    private AuditEntry entry;

    @Setup(Level.Trial)
    public void setup() throws SQLException {
        connection = mock(Connection.class);
        preparedStatement = mock(PreparedStatement.class);
        dataSource = mock(HikariDataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);

        // Chain-hash query mock
        var prevHashStmt = mock(PreparedStatement.class);
        var prevHashRs = mock(java.sql.ResultSet.class);
        doReturn(prevHashStmt).when(connection).prepareStatement(contains("chain_hash"));
        doReturn(prevHashRs).when(prevHashStmt).executeQuery();
        doReturn(false).when(prevHashRs).next();

        doReturn(preparedStatement).when(connection).prepareStatement(contains("INSERT"));

        entry = AuditEntry.builder()
                .actorId("bench-user")
                .action("UPDATE")
                .entityType("Order")
                .entityId("ord-42")
                .changes(Map.of("status", Map.of("old", "A", "new", "B")))
                .metadata(Map.of("bench", true))
                .build();
    }

    /**
     * Single-threaded throughput — misst ops/sec für sequentielle Log-Aufrufe.
     */
    @Benchmark
    public void singleLogThroughput(Blackhole bh) {
        try (var logger = new PostgresAuditLogger(dataSource)) {
            logger.log(entry).join();
            bh.consume(logger);
        }
    }

    /**
     * Concurrent throughput — 4 Threads schreiben parallel.
     */
    @Benchmark
    @Threads(4)
    public void concurrentLogThroughput(Blackhole bh) {
        try (var logger = new PostgresAuditLogger(dataSource)) {
            logger.log(entry).join();
            bh.consume(logger);
        }
    }

    /**
     * Backpressure latency unter Contention — misst Latenz mit maxConcurrency.
     */
    @State(Scope.Thread)
    public static class BackpressureState {
        PostgresAuditLogger logger;
        AuditEntry entry;

        @Setup(Level.Trial)
        public void setup() throws SQLException {
            var conn = mock(Connection.class);
            var ps = mock(PreparedStatement.class);
            var ds = mock(DataSource.class);
            when(ds.getConnection()).thenReturn(conn);

            var prevHashStmt = mock(PreparedStatement.class);
            var prevHashRs = mock(java.sql.ResultSet.class);
            doReturn(prevHashStmt).when(conn).prepareStatement(contains("chain_hash"));
            doReturn(prevHashRs).when(prevHashStmt).executeQuery();
            doReturn(false).when(prevHashRs).next();
            doReturn(ps).when(conn).prepareStatement(contains("INSERT"));

            logger = PostgresAuditLogger.builder().dataSource(ds).maxConcurrency(5).build();
            entry = AuditEntry.builder()
                    .actorId("bp-bench").action("X").entityType("T").entityId("1").build();
        }

        @TearDown(Level.Trial)
        public void teardown() {
            logger.close();
        }
    }

    @Benchmark
    @Threads(10)
    public void backpressureLatency(BackpressureState state, Blackhole bh) {
        state.logger.log(state.entry).join();
        bh.consume(state.logger);
    }
}
