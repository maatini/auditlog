package io.audit.core

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.util.Objects
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import javax.sql.DataSource

class PostgresAuditLogger internal constructor(
    private val dataSource: DataSource,
    private val executor: Executor,
    private val ownsDataSource: Boolean,
    private val ownsExecutor: Boolean,
    private val objectMapper: ObjectMapper,
    private val semaphore: Semaphore?,
    private val backpressurePolicy: BackpressurePolicy?,
    private val errorCallback: Consumer<AuditLoggingException>?
) : AuditLogger {

    enum class BackpressurePolicy {
        BLOCK,
        FAST_FAIL
    }

    constructor(dataSource: DataSource) : this(
        dataSource, DEFAULT_EXECUTOR, false, false, OBJECT_MAPPER, null, null, null
    )

    constructor(
        dataSource: DataSource,
        executor: Executor,
        ownsDataSource: Boolean,
        ownsExecutor: Boolean
    ) : this(dataSource, executor, ownsDataSource, ownsExecutor, OBJECT_MAPPER, null, null, null)

    class Builder {
        private var dataSource: DataSource? = null
        private var executor: Executor? = null
        private var objectMapper: ObjectMapper? = null
        private var maxConcurrency: Int = 0
        private var backpressurePolicy: BackpressurePolicy? = null
        private var errorCallback: Consumer<AuditLoggingException>? = null

        fun dataSource(dataSource: DataSource?): Builder = apply { this.dataSource = dataSource }
        fun executor(executor: Executor?): Builder = apply { this.executor = executor }
        fun objectMapper(objectMapper: ObjectMapper?): Builder = apply { this.objectMapper = objectMapper }
        fun maxConcurrency(maxConcurrency: Int): Builder = apply { this.maxConcurrency = maxConcurrency }
        fun backpressurePolicy(backpressurePolicy: BackpressurePolicy?): Builder = apply { this.backpressurePolicy = backpressurePolicy }
        fun errorCallback(errorCallback: Consumer<AuditLoggingException>?): Builder = apply { this.errorCallback = errorCallback }

        fun build(): PostgresAuditLogger {
            Objects.requireNonNull(dataSource, "dataSource must not be null")
            val exec = executor ?: DEFAULT_EXECUTOR
            val mapper = objectMapper ?: OBJECT_MAPPER
            val sem = if (maxConcurrency > 0) Semaphore(maxConcurrency) else null
            val pol = backpressurePolicy ?: BackpressurePolicy.BLOCK
            return PostgresAuditLogger(dataSource!!, exec, false, false, mapper, sem, pol, errorCallback)
        }
    }

    companion object {
        @JvmField
        val OBJECT_MAPPER: ObjectMapper = ObjectMapper()
            .registerModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

        @JvmField
        val DEFAULT_EXECUTOR: Executor = Executors.newVirtualThreadPerTaskExecutor()

        @JvmStatic
        fun builder(): Builder = Builder()

        private val INSERT_SQL = """
            INSERT INTO audit_log (id, timestamp, actor_id, action, entity_type, entity_id, changes, metadata, chain_hash)
            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
        """.trimIndent()

        private val PREV_HASH_SQL = """
            SELECT chain_hash FROM audit_log ORDER BY timestamp DESC LIMIT 1
        """.trimIndent()

        private val ZERO_HASH = ByteArray(32)
    }

    private val logger = LoggerFactory.getLogger(PostgresAuditLogger::class.java)

    override fun log(entry: AuditEntry): CompletableFuture<Void> {
        val rejected = acquirePermit()
        if (rejected != null) {
            return rejected
        }

        val changesJson: String
        val metadataJson: String
        try {
            changesJson = toJson(entry.changes)
            metadataJson = toJson(entry.metadata)
        } catch (e: RuntimeException) {
            releasePermitAndNotify(e)
            return CompletableFuture.failedFuture(e)
        }

        var future: CompletableFuture<Void>
        try {
            future = CompletableFuture.runAsync({ executeInsert(entry, changesJson, metadataJson) }, executor)
        } catch (e: RejectedExecutionException) {
            releasePermitAndNotify(e)
            return CompletableFuture.failedFuture(e)
        }

        if (semaphore != null) {
            future = future.whenComplete { _, _ -> semaphore.release() }
        }
        return future
    }

    private fun acquirePermit(): CompletableFuture<Void>? {
        if (semaphore == null || semaphore!!.tryAcquire()) {
            return null
        }
        if (backpressurePolicy == BackpressurePolicy.FAST_FAIL) {
            val ex = AuditLoggingException("Backpressure limit reached, all permits exhausted", null)
            errorCallback?.accept(ex)
            return CompletableFuture.failedFuture(ex)
        }
        try {
            semaphore!!.acquire()
            return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return CompletableFuture.failedFuture(
                AuditLoggingException("Interrupted while waiting for backpressure permit", e)
            )
        }
    }

    private fun releasePermitAndNotify(e: RuntimeException) {
        semaphore?.release()
        if (errorCallback != null && e is AuditLoggingException) {
            errorCallback!!.accept(e)
        }
    }

    private fun executeInsert(entry: AuditEntry, changesJson: String, metadataJson: String) {
        try {
            insert(entry, changesJson, metadataJson)
        } catch (e: RuntimeException) {
            logger.error("Failed to persist audit entry: {}", entry.id, e)
            errorCallback?.let { cb ->
                val ale = when (e) {
                    is AuditLoggingException -> e
                    else -> AuditLoggingException("Unexpected execution error", e)
                }
                cb.accept(ale)
            }
            throw e
        }
    }

    private fun insert(entry: AuditEntry, changesJson: String, metadataJson: String) {
        try {
            @Suppress("SqlSourceToSinkFlow")
            dataSource.connection.use { conn ->
                val prevHash = queryPrevHash(conn)
                val hash = computeChainHash(prevHash, entry, changesJson, metadataJson)

                conn.prepareStatement(INSERT_SQL).use { ps ->
                    ps.setObject(1, entry.id)
                    ps.setObject(2, entry.timestamp)
                    ps.setString(3, entry.actorId)
                    ps.setString(4, entry.action)
                    ps.setString(5, entry.entityType)
                    ps.setString(6, entry.entityId)
                    ps.setString(7, changesJson)
                    ps.setString(8, metadataJson)
                    ps.setBytes(9, hash)
                    ps.executeUpdate()
                }
            }
        } catch (e: SQLException) {
            throw AuditLoggingException("Database error while writing audit log", e)
        }
    }

    private fun queryPrevHash(conn: Connection): ByteArray {
        conn.prepareStatement(PREV_HASH_SQL).use { ps ->
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val hash = rs.getBytes("chain_hash")
                    return hash ?: ZERO_HASH
                }
                return ZERO_HASH
            }
        }
    }

    private fun computeChainHash(
        prevHash: ByteArray,
        entry: AuditEntry,
        changesJson: String,
        metadataJson: String
    ): ByteArray {
        try {
            val md = MessageDigest.getInstance("SHA-256")
            md.update(prevHash)
            md.update(entry.id.toString().toByteArray())
            md.update(entry.timestamp.toString().toByteArray())
            md.update(entry.actorId.toByteArray())
            md.update(entry.action.toByteArray())
            md.update(entry.entityType.toByteArray())
            md.update(entry.entityId.toByteArray())
            md.update(changesJson.toByteArray())
            md.update(metadataJson.toByteArray())
            return md.digest()
        } catch (e: NoSuchAlgorithmException) {
            throw AuditLoggingException("SHA-256 not available", e)
        }
    }

    private fun toJson(value: Any): String {
        try {
            return objectMapper.writeValueAsString(value)
        } catch (e: JsonProcessingException) {
            throw AuditLoggingException("Failed to serialize audit log field to JSON", e)
        }
    }

    override fun close() {
        closeDataSource()
        closeExecutor()
    }

    private fun closeDataSource() {
        if (!ownsDataSource || dataSource !is AutoCloseable) {
            return
        }
        try {
            (dataSource as AutoCloseable).close()
        } catch (e: Exception) {
            logger.warn("Error closing data source", e)
        }
    }

    private fun closeExecutor() {
        if (!ownsExecutor || executor !is ExecutorService) {
            return
        }
        val es = executor as ExecutorService
        es.shutdown()
        try {
            if (!es.awaitTermination(5, TimeUnit.SECONDS)) {
                es.shutdownNow()
            }
        } catch (e: InterruptedException) {
            es.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }
}
