package io.audit.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Audit-Logger für PostgreSQL mit asynchroner Persistierung via Virtual Threads.
 *
 * <p><b>Backpressure & Pool Starvation:</b> {@link #log(AuditEntry) log()} läuft
 * standardmäßig auf einem Virtual-Thread-Executor. Da Virtual Threads praktisch
 * unlimitiert erzeugt werden können, kann die Anzahl der wartenden Log-Vorgänge
 * die Kapazität des Connection-Pools übersteigen (Standard-Poolgröße bei
 * eigener Pool-Erstellung: 5). Sämtliche Threads konkurrieren dann um dieselben
 * Datenbankverbindungen.
 *
 * <p>Ist der Pool erschöpft, blockiert der nächste {@code log()}-Aufruf so lange,
 * bis eine Verbindung frei wird oder das Connection-Timeout (HikariCP-Standard:
 * 30 s) abläuft. Im letzteren Fall wird eine {@code SQLTransientConnectionException}
 * ausgelöst, die als {@link AuditLoggingException} in der zurückgegebenen
 * {@code CompletableFuture} landen kann.
 *
 * <p><b>Empfehlungen bei hoher Last:</b>
 * <ul>
 *   <li>Connection-Pool ausreichend dimensionieren (Poolgröße = erwartete
 *       parallele Log-Vorgänge + Reserve)</li>
 *   <li>Einen eigenen {@link java.util.concurrent.Executor} mit begrenzter
 *       Parallelität übergeben, z. B. einen virtuellen oder
 *       plattformgebundenen Thread-Pool mit {@link
 *       java.util.concurrent.Executors#newFixedThreadPool(int)}</li>
 *   <li>Auf {@link java.util.concurrent.CompletableFuture#join() join()} im
 *       aufrufenden Thread verzichten, wenn die Reihenfolge der Einträge
 *       nicht garantiert werden muss</li>
 * </ul>
 */
public class PostgresAuditLogger implements AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(PostgresAuditLogger.class);
    static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final Executor DEFAULT_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private static final String INSERT_SQL = """
            INSERT INTO audit_log (id, timestamp, actor_id, action, entity_type, entity_id, changes, metadata)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)
            """;

    private final DataSource dataSource;
    private final Executor executor;
    private final boolean ownsDataSource;
    private final boolean ownsExecutor;
    private final ObjectMapper objectMapper;
    private final Semaphore semaphore;
    private final BackpressurePolicy backpressurePolicy;
    private final Consumer<AuditLoggingException> errorCallback;

    public PostgresAuditLogger(DataSource dataSource) {
        this(dataSource, DEFAULT_EXECUTOR, false, false, OBJECT_MAPPER, null, null, null);
    }

    /**
     * @deprecated Use {@link PostgresAuditLogger#builder()} and configure via
     *             {@link Builder#executor(Executor)} instead.
     */
    @Deprecated(forRemoval = false)
    public PostgresAuditLogger(DataSource dataSource, Executor executor) {
        this(dataSource, executor, false, false, OBJECT_MAPPER, null, null, null);
    }

    /**
     * @deprecated Use {@link PostgresAuditLogger#builder()} and configure via
     *             {@link Builder#objectMapper(ObjectMapper)} instead.
     */
    @Deprecated(forRemoval = false)
    public PostgresAuditLogger(DataSource dataSource, ObjectMapper objectMapper) {
        this(dataSource, DEFAULT_EXECUTOR, false, false, objectMapper, null, null, null);
    }

    /**
     * @deprecated Use {@link PostgresAuditLogger#builder()} and configure via
     *             {@link Builder#executor(Executor)} and
     *             {@link Builder#objectMapper(ObjectMapper)} instead.
     */
    @Deprecated(forRemoval = false)
    public PostgresAuditLogger(DataSource dataSource, Executor executor, ObjectMapper objectMapper) {
        this(dataSource, executor, false, false, objectMapper, null, null, null);
    }

    /**
     * @deprecated Use {@link PostgresAuditLogger#builder()} and configure via
     *             {@link Builder#maxConcurrency(int)} instead.
     */
    @Deprecated(forRemoval = false)
    public PostgresAuditLogger(DataSource dataSource, int maxConcurrency) {
        this(dataSource, DEFAULT_EXECUTOR, false, false, OBJECT_MAPPER,
                new Semaphore(maxConcurrency), BackpressurePolicy.BLOCK, null);
    }

    /**
     * @deprecated Use {@link PostgresAuditLogger#builder()} and configure via
     *             {@link Builder#maxConcurrency(int)} and
     *             {@link Builder#backpressurePolicy(BackpressurePolicy)} instead.
     */
    @Deprecated(forRemoval = false)
    public PostgresAuditLogger(DataSource dataSource, int maxConcurrency, BackpressurePolicy backpressurePolicy) {
        this(dataSource, DEFAULT_EXECUTOR, false, false, OBJECT_MAPPER,
                new Semaphore(maxConcurrency), backpressurePolicy, null);
    }

    /**
     * @deprecated Use {@link PostgresAuditLogger#builder()} and configure via
     *             {@link Builder#maxConcurrency(int)},
     *             {@link Builder#backpressurePolicy(BackpressurePolicy)} and
     *             {@link Builder#errorCallback(Consumer)} instead.
     */
    @Deprecated(forRemoval = false)
    public PostgresAuditLogger(DataSource dataSource, int maxConcurrency, BackpressurePolicy backpressurePolicy,
                               Consumer<AuditLoggingException> errorCallback) {
        this(dataSource, DEFAULT_EXECUTOR, false, false, OBJECT_MAPPER,
                new Semaphore(maxConcurrency), backpressurePolicy, errorCallback);
    }

    /**
     * @deprecated Use {@link PostgresAuditLogger#builder()} and configure via
     *             {@link Builder#executor(Executor)},
     *             {@link Builder#maxConcurrency(int)},
     *             {@link Builder#backpressurePolicy(BackpressurePolicy)} and
     *             {@link Builder#errorCallback(Consumer)} instead.
     */
    @Deprecated(forRemoval = false)
    public PostgresAuditLogger(DataSource dataSource, Executor executor, int maxConcurrency,
                               BackpressurePolicy backpressurePolicy,
                               Consumer<AuditLoggingException> errorCallback) {
        this(dataSource, executor, false, false, OBJECT_MAPPER,
                new Semaphore(maxConcurrency), backpressurePolicy, errorCallback);
    }

    PostgresAuditLogger(DataSource dataSource, Executor executor, boolean ownsDataSource, boolean ownsExecutor) {
        this(dataSource, executor, ownsDataSource, ownsExecutor, OBJECT_MAPPER, null, null, null);
    }

    PostgresAuditLogger(DataSource dataSource, Executor executor, boolean ownsDataSource, boolean ownsExecutor,
                        ObjectMapper objectMapper) {
        this(dataSource, executor, ownsDataSource, ownsExecutor, objectMapper, null, null, null);
    }

    PostgresAuditLogger(DataSource dataSource, Executor executor, boolean ownsDataSource, boolean ownsExecutor,
                        ObjectMapper objectMapper, Semaphore semaphore, BackpressurePolicy backpressurePolicy,
                        Consumer<AuditLoggingException> errorCallback) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.executor = Objects.requireNonNull(executor, "executor must not be null");
        this.ownsDataSource = ownsDataSource;
        this.ownsExecutor = ownsExecutor;
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.semaphore = semaphore;
        this.backpressurePolicy = backpressurePolicy;
        this.errorCallback = errorCallback;
    }

    /**
     * Verhalten bei erschöpfter Backpressure-Kapazität.
     */
    public enum BackpressurePolicy {
        /** Aufrufer blockiert, bis ein Permit frei wird */
        BLOCK,
        /** Aufrufer erhält sofort eine fehlgeschlagene {@code CompletableFuture} */
        FAST_FAIL
    }

    /**
     * Erzeugt einen neuen {@link Builder} für {@link PostgresAuditLogger}.
     *
     * @return ein neuer Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Konfigurierbarer Builder für {@link PostgresAuditLogger}.
     * <p>
     * Alle Felder bis auf {@code dataSource} sind optional.
     */
    public static final class Builder {
        private DataSource dataSource;
        private Executor executor;
        private ObjectMapper objectMapper;
        private int maxConcurrency;
        private BackpressurePolicy backpressurePolicy;
        private Consumer<AuditLoggingException> errorCallback;

        private Builder() {}

        /** @param dataSource die DataSource (Pflichtfeld) */
        public Builder dataSource(DataSource dataSource) {
            this.dataSource = dataSource;
            return this;
        }

        /** @param executor Executor für asynchrone Ausführung (Default: Virtual-Thread-Executor) */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /** @param objectMapper konfigurierter ObjectMapper (Default: {@link #OBJECT_MAPPER}) */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        /** @param maxConcurrency maximale gleichzeitige Log-Vorgänge; 0 = unbegrenzt (Default: 0) */
        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /** @param backpressurePolicy Verhalten bei erschöpftem Semaphor (Default: {@link BackpressurePolicy#BLOCK}) */
        public Builder backpressurePolicy(BackpressurePolicy backpressurePolicy) {
            this.backpressurePolicy = backpressurePolicy;
            return this;
        }

        /** @param errorCallback optionaler Callback für asynchrone Fehler (Fire-and-Forget) */
        public Builder errorCallback(Consumer<AuditLoggingException> errorCallback) {
            this.errorCallback = errorCallback;
            return this;
        }

        /**
         * Erzeugt den {@link PostgresAuditLogger}.
         *
         * @throws NullPointerException wenn {@code dataSource} nicht gesetzt wurde
         */
        public PostgresAuditLogger build() {
            Objects.requireNonNull(dataSource, "dataSource must not be null");
            var exec = executor != null ? executor : DEFAULT_EXECUTOR;
            var mapper = objectMapper != null ? objectMapper : OBJECT_MAPPER;
            var sem = maxConcurrency > 0 ? new Semaphore(maxConcurrency) : null;
            var pol = backpressurePolicy != null ? backpressurePolicy : BackpressurePolicy.BLOCK;
            return new PostgresAuditLogger(dataSource, exec, false, false,
                    mapper, sem, pol, errorCallback);
        }
    }

    @Override
    public CompletableFuture<Void> log(AuditEntry entry) {
        Objects.requireNonNull(entry, "entry must not be null");

        if (semaphore != null) {
            if (!semaphore.tryAcquire()) {
                if (backpressurePolicy == BackpressurePolicy.FAST_FAIL) {
                    var ex = new AuditLoggingException(
                            "Backpressure limit reached, all permits exhausted", null);
                    if (errorCallback != null) {
                        errorCallback.accept(ex);
                    }
                    return CompletableFuture.failedFuture(ex);
                }
                try {
                    semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(
                            new AuditLoggingException("Interrupted while waiting for backpressure permit", e));
                }
            }
        }

        // Synchrone Pre-Serialization: Zustand im Aufrufer-Thread einfrieren
        String changesJson;
        String metadataJson;
        try {
            changesJson = toJson(entry.changes());
            metadataJson = toJson(entry.metadata());
        } catch (RuntimeException e) {
            if (semaphore != null) {
                semaphore.release();
            }
            if (errorCallback != null && e instanceof AuditLoggingException ale) {
                errorCallback.accept(ale);
            }
            return CompletableFuture.failedFuture(e);
        }

        // Entry-Felder für den Lambda-Zugriff extrahieren
        var entryId = entry.id();
        var entryTimestamp = entry.timestamp();
        var entryActorId = entry.actorId();
        var entryAction = entry.action();
        var entryEntityType = entry.entityType();
        var entryEntityId = entry.entityId();

        CompletableFuture<Void> future;
        try {
            future = CompletableFuture.runAsync(() -> {
                try {
                    insert(entryId, entryTimestamp, entryActorId, entryAction,
                            entryEntityType, entryEntityId, changesJson, metadataJson);
                } catch (RuntimeException e) {
                    log.error("Failed to persist audit entry: {}", entryId, e);
                    if (errorCallback != null && e instanceof AuditLoggingException ale) {
                        errorCallback.accept(ale);
                    }
                    throw e;
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            if (semaphore != null) {
                semaphore.release();
            }
            return CompletableFuture.failedFuture(e);
        }

        if (semaphore != null) {
            future = future.whenComplete((v, t) -> semaphore.release());
        }
        return future;
    }

    private void insert(UUID id, OffsetDateTime timestamp,
                        String actorId, String action,
                        String entityType, String entityId,
                        String changesJson, String metadataJson) {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(INSERT_SQL)) {

            ps.setObject(1, id);
            ps.setObject(2, timestamp);
            ps.setString(3, actorId);
            ps.setString(4, action);
            ps.setString(5, entityType);
            ps.setString(6, entityId);
            ps.setString(7, changesJson);
            ps.setString(8, metadataJson);
            ps.executeUpdate();

        } catch (SQLException e) {
            throw new AuditLoggingException("Database error while writing audit log", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new AuditLoggingException("Failed to serialize audit log field to JSON", e);
        }
    }

    @Override
    public void close() {
        if (ownsDataSource && dataSource instanceof AutoCloseable ac) {
            try {
                ac.close();
            } catch (Exception e) {
                log.warn("Error closing data source", e);
            }
        }
        if (ownsExecutor && executor instanceof ExecutorService es) {
            es.shutdown();
            try {
                if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                    es.shutdownNow();
                }
            } catch (InterruptedException e) {
                es.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
