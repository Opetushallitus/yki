CREATE TABLE IF NOT EXISTS exam_session_statistics (
    id BIGSERIAL PRIMARY KEY,
    exam_session_id BIGSERIAL REFERENCES exam_session (id) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
    last_processed_event TIMESTAMP WITH TIME ZONE NOT NULL,
    participants INTEGER NOT NULL,
    queue INTEGER NOT NULL
    -- TODO Also max counts of participants and queue?
    -- TODO Separately also max allowed participants?
    -- TODO Time of highest participant / queue counts?
);

CREATE INDEX IF NOT EXISTS exam_session_statistics_exam_session_id ON
exam_session_statistics (exam_session_id);
