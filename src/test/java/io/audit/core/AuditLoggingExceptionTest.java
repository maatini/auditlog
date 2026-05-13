package io.audit.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AuditLoggingException")
class AuditLoggingExceptionTest {

    @Test
    @DisplayName("wraps cause and message")
    void wrapsCauseAndMessage() {
        var cause = new RuntimeException("root cause");
        var ex = new AuditLoggingException("something went wrong", cause);
        assertEquals("something went wrong", ex.getMessage());
        assertSame(cause, ex.getCause());
    }
}
