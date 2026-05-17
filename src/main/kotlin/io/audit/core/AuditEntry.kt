package io.audit.core

import java.time.OffsetDateTime
import java.util.Collections
import java.util.UUID

data class AuditEntry(
    @get:JvmName("id") val id: UUID,
    @get:JvmName("timestamp") val timestamp: OffsetDateTime,
    @get:JvmName("actorId") val actorId: String,
    @get:JvmName("action") val action: String,
    @get:JvmName("entityType") val entityType: String,
    @get:JvmName("entityId") val entityId: String,
    @get:JvmName("changes") val changes: Map<String, Any>,
    @get:JvmName("metadata") val metadata: Map<String, Any>,
    @get:JvmName("chainHash") val chainHash: ByteArray?
) {
    companion object {
        const val MAX_STRING_LENGTH = 255

        @JvmStatic
        fun builder(): Builder = Builder()
    }

    class Builder {
        private var id: UUID? = null
        private var timestamp: OffsetDateTime? = null
        private var actorId: String? = null
        private var action: String? = null
        private var entityType: String? = null
        private var entityId: String? = null
        private var changes: Map<String, Any> = Collections.emptyMap()
        private var metadata: Map<String, Any> = Collections.emptyMap()

        fun id(id: UUID?): Builder = apply { this.id = id }
        fun timestamp(timestamp: OffsetDateTime?): Builder = apply { this.timestamp = timestamp }
        fun actorId(actorId: String?): Builder = apply { this.actorId = actorId }
        fun action(action: String?): Builder = apply { this.action = action }
        fun entityType(entityType: String?): Builder = apply { this.entityType = entityType }
        fun entityId(entityId: String?): Builder = apply { this.entityId = entityId }

        fun changes(changes: Map<String, Any>?): Builder = apply {
            this.changes = changes ?: Collections.emptyMap()
        }

        fun metadata(metadata: Map<String, Any>?): Builder = apply {
            this.metadata = metadata ?: Collections.emptyMap()
        }

        fun build(): AuditEntry {
            requireNotBlank("actorId", actorId)
            requireNotBlank("action", action)
            requireNotBlank("entityType", entityType)
            requireNotBlank("entityId", entityId)
            return AuditEntry(
                id = id ?: UUID.randomUUID(),
                timestamp = timestamp ?: OffsetDateTime.now(),
                actorId = actorId!!,
                action = action!!,
                entityType = entityType!!,
                entityId = entityId!!,
                changes = Collections.unmodifiableMap(HashMap(changes)),
                metadata = Collections.unmodifiableMap(HashMap(metadata)),
                chainHash = null
            )
        }

        private fun requireNotBlank(name: String, value: String?) {
            require(!value.isNullOrBlank()) { "$name must not be blank" }
            require(value!!.length <= MAX_STRING_LENGTH) {
                "$name exceeds $MAX_STRING_LENGTH characters: ${value.length}"
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuditEntry) return false

        if (id != other.id) return false
        if (timestamp != other.timestamp) return false
        if (actorId != other.actorId) return false
        if (action != other.action) return false
        if (entityType != other.entityType) return false
        if (entityId != other.entityId) return false
        if (changes != other.changes) return false
        if (metadata != other.metadata) return false
        if (chainHash != null) {
            if (other.chainHash == null) return false
            if (!chainHash.contentEquals(other.chainHash)) return false
        } else if (other.chainHash != null) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + actorId.hashCode()
        result = 31 * result + action.hashCode()
        result = 31 * result + entityType.hashCode()
        result = 31 * result + entityId.hashCode()
        result = 31 * result + changes.hashCode()
        result = 31 * result + metadata.hashCode()
        result = 31 * result + (chainHash?.contentHashCode() ?: 0)
        return result
    }
}
