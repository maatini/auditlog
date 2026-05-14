package io.audit.core;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

public record AuditEntry(
        UUID id,
        OffsetDateTime timestamp,
        String actorId,
        String action,
        String entityType,
        String entityId,
        Map<String, Object> changes,
        Map<String, Object> metadata
) {

    static final int MAX_STRING_LENGTH = 255;

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID id;
        private OffsetDateTime timestamp;
        private String actorId;
        private String action;
        private String entityType;
        private String entityId;
        private Map<String, Object> changes = Collections.emptyMap();
        private Map<String, Object> metadata = Collections.emptyMap();

        private Builder() {}

        public Builder id(UUID id) {
            this.id = id;
            return this;
        }

        public Builder timestamp(OffsetDateTime timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder actorId(String actorId) {
            this.actorId = actorId;
            return this;
        }

        public Builder action(String action) {
            this.action = action;
            return this;
        }

        public Builder entityType(String entityType) {
            this.entityType = entityType;
            return this;
        }

        public Builder entityId(String entityId) {
            this.entityId = entityId;
            return this;
        }

        public Builder changes(Map<String, Object> changes) {
            this.changes = changes != null ? changes : Collections.emptyMap();
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata != null ? metadata : Collections.emptyMap();
            return this;
        }

        public AuditEntry build() {
            requireNotBlank("actorId", actorId);
            requireNotBlank("action", action);
            requireNotBlank("entityType", entityType);
            requireNotBlank("entityId", entityId);
            return new AuditEntry(
                    id != null ? id : UUID.randomUUID(),
                    timestamp != null ? timestamp : OffsetDateTime.now(),
                    actorId, action, entityType, entityId,
                    Collections.unmodifiableMap(changes),
                    Collections.unmodifiableMap(metadata));
        }

        private static void requireNotBlank(String name, String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(name + " must not be blank");
            }
            if (value.length() > MAX_STRING_LENGTH) {
                throw new IllegalArgumentException(
                        name + " exceeds " + MAX_STRING_LENGTH + " characters: " + value.length());
            }
        }
    }
}
