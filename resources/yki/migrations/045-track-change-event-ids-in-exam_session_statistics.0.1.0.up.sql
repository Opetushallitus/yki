ALTER TABLE exam_session_statistics DROP COLUMN IF EXISTS last_processed_event;
ALTER TABLE exam_session_statistics ADD COLUMN IF NOT EXISTS last_processed_event_id BIGINT DEFAULT NULL;
ALTER TABLE exam_session_statistics ADD CONSTRAINT exam_session_statistics_last_processed_event_id_fkey
    FOREIGN KEY (last_processed_event_id) REFERENCES registration_change_event (id);
