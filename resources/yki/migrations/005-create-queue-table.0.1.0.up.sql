CREATE TABLE IF NOT EXISTS exam_session_queue (
  id BIGSERIAL PRIMARY KEY,
  email TEXT NOT NULL,
  exam_session_id BIGINT NOT NULL REFERENCES exam_session(id) ON DELETE CASCADE,
  removed_at TIMESTAMP WITH TIME ZONE,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
  UNIQUE (email, exam_session_id)
);
