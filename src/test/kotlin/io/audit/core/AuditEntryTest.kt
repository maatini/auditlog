package io.audit.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.OffsetDateTime
import java.util.UUID

@DisplayName("AuditEntry")
class AuditEntryTest {

    @Test
    @DisplayName("builder creates entry with all fields")
    fun builder_createsEntryWithAllFields() {
        val id = UUID.randomUUID()
        val ts = OffsetDateTime.now()
        val changes = mapOf<String, Any>("status" to mapOf("old" to "A", "new" to "B"))
        val metadata = mapOf<String, Any>("ip" to "10.0.0.1")

        val entry = AuditEntry.builder()
            .id(id)
            .timestamp(ts)
            .actorId("user-1")
            .action("UPDATE")
            .entityType("Order")
            .entityId("order-42")
            .changes(changes)
            .metadata(metadata)
            .build()

        assertEquals(id, entry.id)
        assertEquals(ts, entry.timestamp)
        assertEquals("user-1", entry.actorId)
        assertEquals("UPDATE", entry.action)
        assertEquals("Order", entry.entityType)
        assertEquals("order-42", entry.entityId)
        assertEquals(changes, entry.changes)
        assertEquals(metadata, entry.metadata)
    }

    @Test
    @DisplayName("builder generates default id and timestamp")
    fun builder_generatesDefaultIdAndTimestamp() {
        val entry = AuditEntry.builder()
            .actorId("user-1")
            .action("DELETE")
            .entityType("Invoice")
            .entityId("inv-1")
            .build()

        assertNotNull(entry.id)
        assertNotNull(entry.timestamp)
        assertTrue(entry.timestamp.isAfter(OffsetDateTime.now().minusMinutes(1)))
    }

    @Test
    @DisplayName("builder rejects null actorId")
    fun builder_rejectsNullActorId() {
        val b = AuditEntry.builder()
            .action("READ")
            .entityType("User")
            .entityId("u-1")
        assertThrows(IllegalArgumentException::class.java) { b.build() }
    }

    @Test
    @DisplayName("builder rejects blank actorId")
    fun builder_rejectsBlankActorId() {
        val b = AuditEntry.builder()
            .actorId("  ")
            .action("READ")
            .entityType("User")
            .entityId("u-1")
        assertThrows(IllegalArgumentException::class.java) { b.build() }
    }

    @Test
    @DisplayName("builder rejects null action")
    fun builder_rejectsNullAction() {
        val b = AuditEntry.builder()
            .actorId("user-1")
            .entityType("User")
            .entityId("u-1")
        assertThrows(IllegalArgumentException::class.java) { b.build() }
    }

    @Test
    @DisplayName("builder rejects blank action")
    fun builder_rejectsBlankAction() {
        val b = AuditEntry.builder()
            .actorId("user-1")
            .action("")
            .entityType("User")
            .entityId("u-1")
        assertThrows(IllegalArgumentException::class.java) { b.build() }
    }

    @Test
    @DisplayName("builder rejects null entityType")
    fun builder_rejectsNullEntityType() {
        val b = AuditEntry.builder()
            .actorId("user-1")
            .action("WRITE")
            .entityId("e-1")
        assertThrows(IllegalArgumentException::class.java) { b.build() }
    }

    @Test
    @DisplayName("builder rejects blank entityType")
    fun builder_rejectsBlankEntityType() {
        val b = AuditEntry.builder()
            .actorId("user-1")
            .action("WRITE")
            .entityType("\t")
            .entityId("e-1")
        assertThrows(IllegalArgumentException::class.java) { b.build() }
    }

    @Test
    @DisplayName("builder rejects null entityId")
    fun builder_rejectsNullEntityId() {
        val b = AuditEntry.builder()
            .actorId("user-1")
            .action("WRITE")
            .entityType("Doc")
        assertThrows(IllegalArgumentException::class.java) { b.build() }
    }

    @Test
    @DisplayName("builder rejects blank entityId")
    fun builder_rejectsBlankEntityId() {
        val b = AuditEntry.builder()
            .actorId("user-1")
            .action("WRITE")
            .entityType("Doc")
            .entityId("")
        assertThrows(IllegalArgumentException::class.java) { b.build() }
    }

    @Test
    @DisplayName("builder defaults null changes to empty map")
    fun builder_defaultsNullChangesToEmptyMap() {
        val entry = AuditEntry.builder()
            .actorId("u-1")
            .action("X")
            .entityType("T")
            .entityId("1")
            .changes(null)
            .build()
        assertNotNull(entry.changes)
        assertTrue(entry.changes.isEmpty())
    }

    @Test
    @DisplayName("builder defaults null metadata to empty map")
    fun builder_defaultsNullMetadataToEmptyMap() {
        val entry = AuditEntry.builder()
            .actorId("u-1")
            .action("X")
            .entityType("T")
            .entityId("1")
            .metadata(null)
            .build()
        assertNotNull(entry.metadata)
        assertTrue(entry.metadata.isEmpty())
    }

    @Test
    @DisplayName("builder creates unmodifiable changes map")
    fun builder_createsUnmodifiableChangesMap() {
        val changes = hashMapOf<String, Any>("field" to "old")
        val entry = AuditEntry.builder()
            .actorId("u-1")
            .action("X")
            .entityType("T")
            .entityId("1")
            .changes(changes)
            .build()
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (entry.changes as java.util.Map<String, Any>).put("x", "y")
        }
    }

    @Test
    @DisplayName("builder creates unmodifiable metadata map")
    fun builder_createsUnmodifiableMetadataMap() {
        val meta = hashMapOf<String, Any>("k" to "v")
        val entry = AuditEntry.builder()
            .actorId("u-1")
            .action("X")
            .entityType("T")
            .entityId("1")
            .metadata(meta)
            .build()
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (entry.metadata as java.util.Map<String, Any>).put("a", "b")
        }
    }

    @Test
    @DisplayName("record implements equals and hashCode")
    fun record_equalsAndHashCode() {
        val id = UUID.randomUUID()
        val ts = OffsetDateTime.now()
        val e1 = AuditEntry.builder()
            .id(id).timestamp(ts)
            .actorId("u").action("X").entityType("T").entityId("1")
            .build()
        val e2 = AuditEntry.builder()
            .id(id).timestamp(ts)
            .actorId("u").action("X").entityType("T").entityId("1")
            .build()
        assertEquals(e1, e2)
        assertEquals(e1.hashCode(), e2.hashCode())
    }

    @Test
    @DisplayName("builder rejects actorId longer than 255 characters")
    fun builder_rejectsTooLongActorId() {
        val b = AuditEntry.builder()
            .actorId("x".repeat(256))
            .action("READ")
            .entityType("User")
            .entityId("u-1")
        assertThrows(IllegalArgumentException::class.java) { b.build() }
    }

    @Test
    @DisplayName("builder accepts actorId of exactly 255 characters")
    fun builder_acceptsMaxLengthActorId() {
        assertDoesNotThrow {
            AuditEntry.builder()
                .actorId("x".repeat(255))
                .action("READ")
                .entityType("User")
                .entityId("u-1")
                .build()
        }
    }

    @Test
    @DisplayName("builder rejects action longer than 255 characters")
    fun builder_rejectsTooLongAction() {
        val b = AuditEntry.builder()
            .actorId("u-1")
            .action("x".repeat(256))
            .entityType("User")
            .entityId("u-1")
        assertThrows(IllegalArgumentException::class.java) { b.build() }
    }

    @Test
    @DisplayName("builder rejects entityType longer than 255 characters")
    fun builder_rejectsTooLongEntityType() {
        val b = AuditEntry.builder()
            .actorId("u-1")
            .action("READ")
            .entityType("x".repeat(256))
            .entityId("u-1")
        assertThrows(IllegalArgumentException::class.java) { b.build() }
    }

    @Test
    @DisplayName("builder rejects entityId longer than 255 characters")
    fun builder_rejectsTooLongEntityId() {
        val b = AuditEntry.builder()
            .actorId("u-1")
            .action("READ")
            .entityType("User")
            .entityId("x".repeat(256))
        assertThrows(IllegalArgumentException::class.java) { b.build() }
    }
}
