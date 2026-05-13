CREATE TABLE IF NOT EXISTS audit_log (
    id          UUID            PRIMARY KEY,
    timestamp   TIMESTAMPTZ     NOT NULL,
    actor_id    VARCHAR(255)    NOT NULL,
    action      VARCHAR(255)    NOT NULL,
    entity_type VARCHAR(255)    NOT NULL,
    entity_id   VARCHAR(255)    NOT NULL,
    changes     JSONB,
    metadata    JSONB
);

CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON audit_log (timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor     ON audit_log (actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_entity    ON audit_log (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_action    ON audit_log (action);
