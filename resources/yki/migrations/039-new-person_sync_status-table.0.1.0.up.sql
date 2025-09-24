CREATE TABLE IF NOT EXISTS person_sync_status
(
    id BIGSERIAL PRIMARY KEY,
    person_oid TEXT REFERENCES person(oid),
    success_at TIMESTAMP WITH TIME ZONE,
    failed_at TIMESTAMP WITH TIME ZONE,
    created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);

CREATE INDEX IF NOT EXISTS person_sync_status_created_idx ON person_sync_status (created);

INSERT INTO task_lock (task, last_executed) VALUES ('PERSONS_SYNC_HANDLER', '-infinity');
