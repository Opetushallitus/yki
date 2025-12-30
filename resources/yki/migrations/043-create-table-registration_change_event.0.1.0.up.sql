CREATE TABLE IF NOT EXISTS registration_change_event (
    id BIGSERIAL PRIMARY KEY,
    event TEXT NOT NULL,
    registration_id BIGSERIAL REFERENCES registration (id) NOT NULL,
    exam_session_id BIGSERIAL REFERENCES exam_session (id) NOT NULL,
    registration_state registration_state NOT NULL,
    registration_kind registration_kind NOT NULL,
    original_exam_session_id BIGSERIAL REFERENCES exam_session (id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
    created_by TEXT,
    author_type TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS registration_change_event_exam_session_id_created_at
ON registration_change_event (exam_session_id, created_at);
