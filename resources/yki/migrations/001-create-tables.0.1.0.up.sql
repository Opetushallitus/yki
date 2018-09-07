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
  deleted_at TIMESTAMP DEFAULT NULL,
  created TIMESTAMP DEFAULT current_timestamp,
  modified TIMESTAMP DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS exam_language (
  id BIGSERIAL PRIMARY KEY,
  language_code CHAR(2) REFERENCES language (code) NOT NULL,
  level_code TEXT REFERENCES exam_level (code) NOT NULL,
  organizer_id BIGSERIAL NOT NULL REFERENCES organizer(id),
  created TIMESTAMP DEFAULT current_timestamp
);
--;;
CREATE UNIQUE INDEX organizer_oid
ON organizer(oid)
WHERE deleted_at IS NULL;
--;;
CREATE TABLE IF NOT EXISTS cas_ticket (
  ticket TEXT PRIMARY KEY,
  session_id TEXT NOT NULL,
  created TIMESTAMP DEFAULT current_timestamp
);
