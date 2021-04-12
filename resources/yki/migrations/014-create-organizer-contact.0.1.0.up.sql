CREATE TABLE IF NOT EXISTS contact (
  id BIGSERIAL PRIMARY KEY,
  organizer_id BIGSERIAL NOT NULL REFERENCES organizer(id),
  name TEXT,
  email TEXT,
  phone_number TEXT,
  deleted_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
  modified TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS exam_session_contact (
  id BIGSERIAL PRIMARY KEY,
  exam_session_id BIGSERIAL NOT NULL REFERENCES exam_session(id) ON DELETE CASCADE,
  contact_id BIGSERIAL NOT NULL REFERENCES contact(id) ON DELETE CASCADE,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
  deleted_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
  UNIQUE (exam_session_id, contact_id)
);
