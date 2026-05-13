package io.audit.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AuditLogger")
class AuditLoggerTest {

    @Test
    @DisplayName("default close does nothing")
    void defaultCloseDoesNothing() {
        AuditLogger logger = entry -> CompletableFuture.completedFuture(null);
        assertDoesNotThrow(logger::close);
    }
}
