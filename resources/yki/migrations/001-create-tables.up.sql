CREATE TABLE IF NOT EXISTS language (
  code CHAR(2) PRIMARY KEY
);
--;;
CREATE TABLE IF NOT EXISTS exam_level (
  code TEXT PRIMARY KEY
);
--;;
CREATE TABLE IF NOT EXISTS organizer (
  oid TEXT PRIMARY KEY,
  agreement_start_date DATE NOT NULL,
  agreement_end_date DATE NOT NULL,
  contact_name TEXT,
  contact_email TEXT,
  contact_phone_number TEXT
);
--;;
CREATE TABLE IF NOT EXISTS exam_language (
  id BIGSERIAL PRIMARY KEY,
  language_code CHAR(2) REFERENCES language (code) NOT NULL,
  level_code TEXT REFERENCES exam_level (code) NOT NULL,
  organizer_id TEXT NOT NULL REFERENCES organizer(oid)
);
