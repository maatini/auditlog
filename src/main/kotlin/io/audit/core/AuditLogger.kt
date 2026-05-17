package io.audit.core

import java.util.concurrent.CompletableFuture

fun interface AuditLogger : AutoCloseable {
    fun log(entry: AuditEntry): CompletableFuture<Void>

    override fun close() {}
}
