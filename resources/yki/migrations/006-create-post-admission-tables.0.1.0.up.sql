CREATE TABLE IF NOT EXISTS post_admission (
  id BIGSERIAL PRIMARY KEY,
  admission_name TEXT,
  exam_session_id BIGINT NOT NULL REFERENCES exam_session(id),
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  quota INTEGER NOT NULL,
  notify_queue BOOLEAN DEFAULT TRUE NOT NULL
);

CREATE UNIQUE INDEX post_admission_id
    on post_admission (id);

CREATE TABLE IF NOT EXISTS post_admission_participant (
    registration_id BIGINT NOT NULL REFERENCES registration(id),
    post_admission_id BIGINT NOT NULL REFERENCES post_admission(id),
    PRIMARY KEY (registration_id, post_admission_id)
);
