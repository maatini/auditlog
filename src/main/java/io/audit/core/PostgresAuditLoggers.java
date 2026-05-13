package io.audit.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/**
 * Factory für {@link PostgresAuditLogger}.
 *
 * Die Methoden dieser Klasse benötigen HikariCP auf dem Klassenpfad.
 * Wer einen eigenen Connection-Pool nutzt, kann {@code PostgresAuditLogger}
 * direkt über den {@code DataSource}-Konstruktor erstellen.
 */
public final class PostgresAuditLoggers {

    private PostgresAuditLoggers() {}

    /**
     * Erzeugt einen Logger mit eigenem HikariCP-Connection-Pool.
     *
     * @param jdbcUrl  JDBC-URL der PostgreSQL-Datenbank
     * @param username Datenbank-Benutzer
     * @param password Datenbank-Passwort
     */
    public static PostgresAuditLogger create(String jdbcUrl, String username, String password) {
        return new PostgresAuditLogger(createDataSource(jdbcUrl, username, password),
                PostgresAuditLogger.DEFAULT_EXECUTOR, true, false);
    }

    /**
     * Erzeugt einen Logger mit eigenem HikariCP-Connection-Pool und benutzerdefiniertem Executor.
     *
     * @param jdbcUrl  JDBC-URL der PostgreSQL-Datenbank
     * @param username Datenbank-Benutzer
     * @param password Datenbank-Passwort
     * @param executor Executor für asynchrone Log-Ausführung
     */
    public static PostgresAuditLogger create(String jdbcUrl, String username, String password, Executor executor) {
        return new PostgresAuditLogger(createDataSource(jdbcUrl, username, password),
                Objects.requireNonNull(executor, "executor must not be null"), true, false);
    }

    private static HikariDataSource createDataSource(String jdbcUrl, String username, String password) {
        return createDataSource(jdbcUrl, username, password, 0);
    }

    private static HikariDataSource createDataSource(String jdbcUrl, String username, String password,
                                                     int initializationFailTimeout) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setPoolName("audit-log-pool");
        config.setInitializationFailTimeout(initializationFailTimeout);
        return new HikariDataSource(config);
    }

    /**
     * Erzeugt einen neuen {@link Builder} für {@link PostgresAuditLogger}.
     * <p>
     * Der Builder erlaubt die vollständige Kontrolle über HikariCP-Pool-Einstellungen
     * sowie optionale Angabe von Executor und ObjectMapper.
     *
     * @return ein neuer Builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Konfigurierbarer Builder für {@link PostgresAuditLogger} mit eigenem HikariCP-Pool.
     */
    public static final class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private int maximumPoolSize = 5;
        private int minimumIdle = 1;
        private String poolName = "audit-log-pool";
        private Executor executor;
        private ObjectMapper objectMapper;
        private int maxConcurrency;
        private int initializationFailTimeout;
        private PostgresAuditLogger.BackpressurePolicy backpressurePolicy;
        private Consumer<AuditLoggingException> errorCallback;

        private Builder() {}

        /**
         * @param jdbcUrl JDBC-URL der PostgreSQL-Datenbank (Pflichtfeld)
         */
        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        /**
         * @param username Datenbank-Benutzer (Pflichtfeld)
         */
        public Builder username(String username) {
            this.username = username;
            return this;
        }

        /**
         * @param password Datenbank-Passwort (Pflichtfeld)
         */
        public Builder password(String password) {
            this.password = password;
            return this;
        }

        /**
         * Setzt die maximale Pool-Größe (Default: 5).
         */
        public Builder maximumPoolSize(int maximumPoolSize) {
            this.maximumPoolSize = maximumPoolSize;
            return this;
        }

        /**
         * Setzt die minimale Anzahl idle-Verbindungen (Default: 1).
         */
        public Builder minimumIdle(int minimumIdle) {
            this.minimumIdle = minimumIdle;
            return this;
        }

        /**
         * Setzt den Pool-Namen (Default: {@code "audit-log-pool"}).
         */
        public Builder poolName(String poolName) {
            this.poolName = poolName;
            return this;
        }

        /**
         * Setzt einen eigenen Executor für asynchrone Log-Ausführung.
         * Wird keiner gesetzt, wird ein Virtual-Thread-Executor ({@link PostgresAuditLogger#DEFAULT_EXECUTOR}) verwendet.
         */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        /**
         * Setzt einen eigenen ObjectMapper (z.&#8239;B. mit zusätzlichen Modulen).
         * Wird keiner gesetzt, wird der Standard-ObjectMapper verwendet.
         */
        public Builder objectMapper(ObjectMapper objectMapper) {
            this.objectMapper = objectMapper;
            return this;
        }

        /**
         * Setzt die maximale Anzahl gleichzeitiger Log-Vorgänge (Backpressure).
         * Default: 0 (kein Backpressure).
         */
        public Builder maxConcurrency(int maxConcurrency) {
            this.maxConcurrency = maxConcurrency;
            return this;
        }

        /**
         * Setzt das Initialisierungs-Timeout für den HikariCP-Pool in Millisekunden.
         * Default: 0 (kein Fail-Fast — Pool startet auch ohne DB).
         * Auf {@code -1} setzen, um bei nicht erreichbarer DB sofort zu scheitern.
         */
        public Builder initializationFailTimeout(int timeoutMs) {
            this.initializationFailTimeout = timeoutMs;
            return this;
        }

        /**
         * Setzt die Backpressure-Policy (Default: {@link PostgresAuditLogger.BackpressurePolicy#BLOCK}).
         * Wirkt nur, wenn {@code maxConcurrency > 0} gesetzt ist.
         */
        public Builder backpressurePolicy(PostgresAuditLogger.BackpressurePolicy backpressurePolicy) {
            this.backpressurePolicy = backpressurePolicy;
            return this;
        }

        /**
         * Setzt einen optionalen Error-Callback für asynchrone Fehler (Fire-and-Forget).
         */
        public Builder errorCallback(Consumer<AuditLoggingException> errorCallback) {
            this.errorCallback = errorCallback;
            return this;
        }

        /**
         * Erzeugt den {@link PostgresAuditLogger} mit den konfigurierten Einstellungen.
         *
         * @throws NullPointerException wenn jdbcUrl, username oder password nicht gesetzt wurden
         */
        public PostgresAuditLogger build() {
            Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null");
            Objects.requireNonNull(username, "username must not be null");
            Objects.requireNonNull(password, "password must not be null");

            var config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            config.setUsername(username);
            config.setPassword(password);
            config.setMaximumPoolSize(maximumPoolSize);
            config.setMinimumIdle(minimumIdle);
            config.setPoolName(poolName);
            config.setInitializationFailTimeout(initializationFailTimeout);
            var dataSource = new HikariDataSource(config);

            var exec = executor != null ? executor : PostgresAuditLogger.DEFAULT_EXECUTOR;
            boolean ownsExec = false;

            var semaphore = maxConcurrency > 0 ? new Semaphore(maxConcurrency) : null;
            var policy = backpressurePolicy != null
                    ? backpressurePolicy
                    : PostgresAuditLogger.BackpressurePolicy.BLOCK;
            var cb = errorCallback;

            if (objectMapper != null) {
                return new PostgresAuditLogger(dataSource, exec, true, ownsExec, objectMapper,
                        semaphore, policy, cb);
            }
            return new PostgresAuditLogger(dataSource, exec, true, ownsExec,
                    PostgresAuditLogger.OBJECT_MAPPER, semaphore, policy, cb);
        }
    }
}
