CREATE TABLE IF NOT EXISTS language (
  code CHAR(2) PRIMARY KEY,
  created TIMESTAMP DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS exam_level (
  code TEXT PRIMARY KEY,
  created TIMESTAMP DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS organizer (
  id BIGSERIAL PRIMARY KEY,
  oid TEXT,
  agreement_start_date DATE NOT NULL,
  agreement_end_date DATE NOT NULL,
  contact_name TEXT,
  contact_email TEXT,
  contact_phone_number TEXT,
  contact_shared_email TEXT,
  deleted_at TIMESTAMP DEFAULT NULL,
  created TIMESTAMP DEFAULT current_timestamp,
  modified TIMESTAMP DEFAULT current_timestamp
);
--;;
CREATE UNIQUE INDEX organizer_oid
ON organizer(oid)
WHERE deleted_at IS NULL;
--;;
CREATE TABLE IF NOT EXISTS exam_language (
  id BIGSERIAL PRIMARY KEY,
  language_code CHAR(2) REFERENCES language (code) NOT NULL,
  level_code TEXT REFERENCES exam_level (code) NOT NULL,
  organizer_id BIGSERIAL NOT NULL REFERENCES organizer(id),
  created TIMESTAMP DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS attachment_metadata (
  external_id TEXT PRIMARY KEY,
  organizer_id BIGSERIAL NOT NULL REFERENCES organizer(id),
  type TEXT,
  deleted_at TIMESTAMP DEFAULT NULL,
  created TIMESTAMP DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS exam_session (
  id BIGSERIAL PRIMARY KEY,
  organizer_id BIGSERIAL NOT NULL REFERENCES organizer(id),
  session_date DATE NOT NULL,
  session_start_time TIME NOT NULL,
  session_end_time TIME NOT NULL,
  registration_start_date DATE NOT NULL,
  registration_start_time TIME NOT NULL,
  registration_end_date DATE NOT NULL,
  registration_end_time TIME NOT NULL,
  max_participants INTEGER NOT NULL,
  published_at TIMESTAMP DEFAULT NULL,
  created TIMESTAMP DEFAULT current_timestamp,
  modified TIMESTAMP DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS exam_session_location (
  id BIGSERIAL PRIMARY KEY,
  street_address TEXT NOT NULL,
  city TEXT NOT NULL,
  other_location_info TEXT NOT NULL,
  extra_information TEXT,
  language_code CHAR(2) REFERENCES language (code) NOT NULL,
  exam_session_id BIGINT NOT NULL REFERENCES exam_session (id) ON DELETE CASCADE
);
--;;
CREATE TABLE IF NOT EXISTS exam_date (
  id BIGSERIAL PRIMARY KEY,
  exam_date DATE NOT NULL,
  created TIMESTAMP DEFAULT current_timestamp
);
