package io.audit.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AuditEntry")
class AuditEntryTest {

    @Test
    @DisplayName("builder creates entry with all fields")
    void builder_createsEntryWithAllFields() {
        var id = UUID.randomUUID();
        var ts = OffsetDateTime.now();
        var changes = Map.<String, Object>of("status", Map.of("old", "A", "new", "B"));
        var metadata = Map.<String, Object>of("ip", "10.0.0.1");

        var entry = AuditEntry.builder()
                .id(id)
                .timestamp(ts)
                .actorId("user-1")
                .action("UPDATE")
                .entityType("Order")
                .entityId("order-42")
                .changes(changes)
                .metadata(metadata)
                .build();

        assertEquals(id, entry.id());
        assertEquals(ts, entry.timestamp());
        assertEquals("user-1", entry.actorId());
        assertEquals("UPDATE", entry.action());
        assertEquals("Order", entry.entityType());
        assertEquals("order-42", entry.entityId());
        assertEquals(changes, entry.changes());
        assertEquals(metadata, entry.metadata());
    }

    @Test
    @DisplayName("builder generates default id and timestamp")
    void builder_generatesDefaultIdAndTimestamp() {
        var entry = AuditEntry.builder()
                .actorId("user-1")
                .action("DELETE")
                .entityType("Invoice")
                .entityId("inv-1")
                .build();

        assertNotNull(entry.id());
        assertNotNull(entry.timestamp());
        assertTrue(entry.timestamp().isAfter(OffsetDateTime.now().minusMinutes(1)));
    }

    @Test
    @DisplayName("builder rejects null actorId")
    void builder_rejectsNullActorId() {
        var b = AuditEntry.builder()
                .action("READ")
                .entityType("User")
                .entityId("u-1");
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    @DisplayName("builder rejects blank actorId")
    void builder_rejectsBlankActorId() {
        var b = AuditEntry.builder()
                .actorId("  ")
                .action("READ")
                .entityType("User")
                .entityId("u-1");
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    @DisplayName("builder rejects null action")
    void builder_rejectsNullAction() {
        var b = AuditEntry.builder()
                .actorId("user-1")
                .entityType("User")
                .entityId("u-1");
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    @DisplayName("builder rejects blank action")
    void builder_rejectsBlankAction() {
        var b = AuditEntry.builder()
                .actorId("user-1")
                .action("")
                .entityType("User")
                .entityId("u-1");
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    @DisplayName("builder rejects null entityType")
    void builder_rejectsNullEntityType() {
        var b = AuditEntry.builder()
                .actorId("user-1")
                .action("WRITE")
                .entityId("e-1");
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    @DisplayName("builder rejects blank entityType")
    void builder_rejectsBlankEntityType() {
        var b = AuditEntry.builder()
                .actorId("user-1")
                .action("WRITE")
                .entityType("\t")
                .entityId("e-1");
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    @DisplayName("builder rejects null entityId")
    void builder_rejectsNullEntityId() {
        var b = AuditEntry.builder()
                .actorId("user-1")
                .action("WRITE")
                .entityType("Doc");
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    @DisplayName("builder rejects blank entityId")
    void builder_rejectsBlankEntityId() {
        var b = AuditEntry.builder()
                .actorId("user-1")
                .action("WRITE")
                .entityType("Doc")
                .entityId("");
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    @DisplayName("builder defaults null changes to empty map")
    void builder_defaultsNullChangesToEmptyMap() {
        var entry = AuditEntry.builder()
                .actorId("u-1")
                .action("X")
                .entityType("T")
                .entityId("1")
                .changes(null)
                .build();
        assertNotNull(entry.changes());
        assertTrue(entry.changes().isEmpty());
    }

    @Test
    @DisplayName("builder defaults null metadata to empty map")
    void builder_defaultsNullMetadataToEmptyMap() {
        var entry = AuditEntry.builder()
                .actorId("u-1")
                .action("X")
                .entityType("T")
                .entityId("1")
                .metadata(null)
                .build();
        assertNotNull(entry.metadata());
        assertTrue(entry.metadata().isEmpty());
    }

    @Test
    @DisplayName("builder creates unmodifiable changes map")
    void builder_createsUnmodifiableChangesMap() {
        var changes = new HashMap<String, Object>();
        changes.put("field", "old");
        var entry = AuditEntry.builder()
                .actorId("u-1")
                .action("X")
                .entityType("T")
                .entityId("1")
                .changes(changes)
                .build();
        assertThrows(UnsupportedOperationException.class, () -> entry.changes().put("x", "y"));
    }

    @Test
    @DisplayName("builder creates unmodifiable metadata map")
    void builder_createsUnmodifiableMetadataMap() {
        var meta = new HashMap<String, Object>();
        meta.put("k", "v");
        var entry = AuditEntry.builder()
                .actorId("u-1")
                .action("X")
                .entityType("T")
                .entityId("1")
                .metadata(meta)
                .build();
        assertThrows(UnsupportedOperationException.class, () -> entry.metadata().put("a", "b"));
    }

    @Test
    @DisplayName("record implements equals and hashCode")
    void record_equalsAndHashCode() {
        var id = UUID.randomUUID();
        var ts = OffsetDateTime.now();
        var e1 = AuditEntry.builder()
                .id(id).timestamp(ts)
                .actorId("u").action("X").entityType("T").entityId("1")
                .build();
        var e2 = AuditEntry.builder()
                .id(id).timestamp(ts)
                .actorId("u").action("X").entityType("T").entityId("1")
                .build();
        assertEquals(e1, e2);
        assertEquals(e1.hashCode(), e2.hashCode());
    }

    @Test
    @DisplayName("builder rejects actorId longer than 255 characters")
    void builder_rejectsTooLongActorId() {
        var b = AuditEntry.builder()
                .actorId("x".repeat(256))
                .action("READ")
                .entityType("User")
                .entityId("u-1");
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    @DisplayName("builder accepts actorId of exactly 255 characters")
    void builder_acceptsMaxLengthActorId() {
        assertDoesNotThrow(() -> AuditEntry.builder()
                .actorId("x".repeat(255))
                .action("READ")
                .entityType("User")
                .entityId("u-1")
                .build());
    }

    @Test
    @DisplayName("builder rejects action longer than 255 characters")
    void builder_rejectsTooLongAction() {
        var b = AuditEntry.builder()
                .actorId("u-1")
                .action("x".repeat(256))
                .entityType("User")
                .entityId("u-1");
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    @DisplayName("builder rejects entityType longer than 255 characters")
    void builder_rejectsTooLongEntityType() {
        var b = AuditEntry.builder()
                .actorId("u-1")
                .action("READ")
                .entityType("x".repeat(256))
                .entityId("u-1");
        assertThrows(IllegalArgumentException.class, b::build);
    }

    @Test
    @DisplayName("builder rejects entityId longer than 255 characters")
    void builder_rejectsTooLongEntityId() {
        var b = AuditEntry.builder()
                .actorId("u-1")
                .action("READ")
                .entityType("User")
                .entityId("x".repeat(256));
        assertThrows(IllegalArgumentException.class, b::build);
    }
}
