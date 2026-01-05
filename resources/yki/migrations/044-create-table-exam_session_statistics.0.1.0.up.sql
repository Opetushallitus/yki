CREATE TABLE IF NOT EXISTS exam_session_statistics (
    id BIGSERIAL PRIMARY KEY,
    exam_session_id BIGSERIAL REFERENCES exam_session (id) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
    last_processed_event TIMESTAMP WITH TIME ZONE NOT NULL,
    participants INTEGER NOT NULL,
    queue INTEGER NOT NULL,
    max_participant_count INTEGER NOT NULL,
    max_queue_count INTEGER NOT NULL,
    max_participants_at TIMESTAMP WITH TIME ZONE NOT NULL,
    max_queue_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX IF NOT EXISTS exam_session_statistics_exam_session_id ON
exam_session_statistics (exam_session_id);

INSERT INTO task_lock (task, last_executed) VALUES ('EXAM_SESSION_STATISTICS_HANDLER', '-infinity');
