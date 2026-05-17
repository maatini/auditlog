package io.audit.core

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

@DisplayName("AuditLogger")
class AuditLoggerTest {

    @Test
    @DisplayName("default close does nothing")
    fun defaultCloseDoesNothing() {
        val logger = AuditLogger { CompletableFuture.completedFuture(null) }
        assertDoesNotThrow { logger.close() }
    }
}
