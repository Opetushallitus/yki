ALTER TABLE exam_session ADD COLUMN IF NOT EXISTS last_sync_at TIMESTAMPTZ DEFAULT NULL;

UPDATE exam_session SET last_sync_at = NOW() WHERE last_sync_at IS NULL;

INSERT INTO task_lock (task, last_executed)
VALUES ('EXAM_SESSION_SOLKI_SYNC_HANDLER', '-infinity');
