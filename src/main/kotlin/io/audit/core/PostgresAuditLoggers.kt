package io.audit.core

import com.fasterxml.jackson.databind.ObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.util.concurrent.Executor
import java.util.function.Consumer

object PostgresAuditLoggers {

    @JvmStatic
    fun create(jdbcUrl: String, username: String, password: String): PostgresAuditLogger {
        return PostgresAuditLogger(createDataSource(jdbcUrl, username, password))
    }

    @JvmStatic
    fun create(jdbcUrl: String, username: String, password: String, executor: Executor): PostgresAuditLogger {
        val ds = createDataSource(jdbcUrl, username, password)
        return PostgresAuditLogger.builder()
            .dataSource(ds)
            .executor(java.util.Objects.requireNonNull(executor, "executor must not be null"))
            .build()
    }

    private fun createDataSource(jdbcUrl: String, username: String, password: String): HikariDataSource {
        return createDataSource(jdbcUrl, username, password, 0)
    }

    private fun createDataSource(
        jdbcUrl: String,
        username: String,
        password: String,
        initializationFailTimeout: Int
    ): HikariDataSource {
        val config = HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            this.username = username
            this.password = password
            maximumPoolSize = 5
            minimumIdle = 1
            poolName = "audit-log-pool"
            this.initializationFailTimeout = initializationFailTimeout.toLong()
        }
        return HikariDataSource(config)
    }

    @JvmStatic
    fun builder(): Builder = Builder()

    class Builder {
        private var jdbcUrl: String? = null
        private var username: String? = null
        private var password: String? = null
        private var maximumPoolSize: Int = 5
        private var minimumIdle: Int = 1
        private var poolName: String = "audit-log-pool"
        private var executor: Executor? = null
        private var objectMapper: ObjectMapper? = null
        private var maxConcurrency: Int = 0
        private var initializationFailTimeout: Int = 0
        private var backpressurePolicy: PostgresAuditLogger.BackpressurePolicy? = null
        private var errorCallback: Consumer<AuditLoggingException>? = null

        fun jdbcUrl(jdbcUrl: String?): Builder = apply { this.jdbcUrl = jdbcUrl }
        fun username(username: String?): Builder = apply { this.username = username }
        fun password(password: String?): Builder = apply { this.password = password }
        fun maximumPoolSize(maximumPoolSize: Int): Builder = apply { this.maximumPoolSize = maximumPoolSize }
        fun minimumIdle(minimumIdle: Int): Builder = apply { this.minimumIdle = minimumIdle }
        fun poolName(poolName: String?): Builder = apply { this.poolName = poolName ?: "audit-log-pool" }
        fun executor(executor: Executor?): Builder = apply { this.executor = executor }
        fun objectMapper(objectMapper: ObjectMapper?): Builder = apply { this.objectMapper = objectMapper }
        fun maxConcurrency(maxConcurrency: Int): Builder = apply { this.maxConcurrency = maxConcurrency }
        fun initializationFailTimeout(timeoutMs: Int): Builder = apply { this.initializationFailTimeout = timeoutMs }
        fun backpressurePolicy(backpressurePolicy: PostgresAuditLogger.BackpressurePolicy?): Builder = apply { this.backpressurePolicy = backpressurePolicy }
        fun errorCallback(errorCallback: Consumer<AuditLoggingException>?): Builder = apply { this.errorCallback = errorCallback }

        fun build(): PostgresAuditLogger {
            java.util.Objects.requireNonNull(jdbcUrl, "jdbcUrl must not be null")
            java.util.Objects.requireNonNull(username, "username must not be null")
            java.util.Objects.requireNonNull(password, "password must not be null")

            val config = HikariConfig().apply {
                this.jdbcUrl = this@Builder.jdbcUrl
                this.username = this@Builder.username
                this.password = this@Builder.password
                this.maximumPoolSize = this@Builder.maximumPoolSize
                this.minimumIdle = this@Builder.minimumIdle
                this.poolName = this@Builder.poolName
                this.initializationFailTimeout = this@Builder.initializationFailTimeout.toLong()
            }
            val dataSource = HikariDataSource(config)

            val exec = executor ?: PostgresAuditLogger.DEFAULT_EXECUTOR
            val pol = backpressurePolicy
                ?: PostgresAuditLogger.BackpressurePolicy.BLOCK

            val builder = PostgresAuditLogger.builder()
                .dataSource(dataSource)
                .executor(exec)
                .maxConcurrency(maxConcurrency)
                .backpressurePolicy(pol)
            objectMapper?.let { builder.objectMapper(it) }
            errorCallback?.let { builder.errorCallback(it) }
            return builder.build()
        }
    }
}
