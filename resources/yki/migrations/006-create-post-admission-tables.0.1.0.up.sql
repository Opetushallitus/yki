CREATE TABLE IF NOT EXISTS post_admission (
  id BIGSERIAL PRIMARY KEY,
  exam_session_id BIGINT UNIQUE NOT NULL REFERENCES exam_session(id),
  start_date DATE NOT NULL,
  end_date DATE NOT NULL,
  quota INTEGER NOT NULL CHECK (quota > 0),
  modified TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);

CREATE UNIQUE INDEX post_admission_id
    on post_admission (id);

CREATE TABLE IF NOT EXISTS post_admission_participant (
    registration_id BIGINT NOT NULL REFERENCES registration(id),
    post_admission_id BIGINT NOT NULL REFERENCES post_admission(id),
    PRIMARY KEY (registration_id, post_admission_id)
);
