CREATE TABLE IF NOT EXISTS audit_log (
    id          UUID            PRIMARY KEY,
    timestamp   TIMESTAMPTZ     NOT NULL,
    actor_id    VARCHAR(255)    NOT NULL,
    action      VARCHAR(255)    NOT NULL,
    entity_type VARCHAR(255)    NOT NULL,
    entity_id   VARCHAR(255)    NOT NULL,
    changes     JSONB,
    metadata    JSONB,
    chain_hash  BYTEA           NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_log_timestamp ON audit_log (timestamp);
CREATE INDEX IF NOT EXISTS idx_audit_log_actor     ON audit_log (actor_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_entity    ON audit_log (entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_log_action    ON audit_log (action);

-- Append-only protection: prevent UPDATE and DELETE on audit_log
CREATE OR REPLACE FUNCTION block_audit_log_modification()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'Audit log entries are immutable and cannot be modified or deleted.';
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_audit_log_append_only ON audit_log;
CREATE TRIGGER trg_audit_log_append_only
    BEFORE UPDATE OR DELETE ON audit_log
    FOR EACH ROW
    EXECUTE FUNCTION block_audit_log_modification();
