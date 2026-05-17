package io.audit.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AuditLoggingException")
class AuditLoggingExceptionTest {

    @Test
    @DisplayName("wraps cause and message")
    fun wrapsCauseAndMessage() {
        val cause = RuntimeException("root cause")
        val ex = AuditLoggingException("something went wrong", cause)
        assertEquals("something went wrong", ex.message)
        assertSame(cause, ex.cause)
    }
}
