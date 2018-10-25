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
  extra_information TEXT,
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
CREATE TABLE IF NOT EXISTS exam_date (
  id BIGSERIAL PRIMARY KEY,
  exam_date DATE NOT NULL,
  registration_start_date DATE NOT NULL,
  registration_end_date DATE NOT NULL,
  created TIMESTAMP DEFAULT current_timestamp,
  modified TIMESTAMP DEFAULT current_timestamp,
  UNIQUE (exam_date, registration_start_date, registration_end_date)
);
--;;
CREATE TABLE IF NOT EXISTS exam_date_language (
  id BIGSERIAL PRIMARY KEY,
  exam_date_id BIGSERIAL REFERENCES exam_date(id) NOT NULL,
  language_code CHAR(2) REFERENCES language(code) NOT NULL,
  created TIMESTAMP DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS exam_session (
  id BIGSERIAL PRIMARY KEY,
  organizer_id BIGSERIAL NOT NULL REFERENCES organizer(id),
  exam_language_id BIGSERIAL NOT NULL REFERENCES exam_language(id),
  exam_date_id BIGSERIAL NOT NULL REFERENCES exam_date(id),
  max_participants INTEGER NOT NULL,
  published_at TIMESTAMP DEFAULT NULL,
  created TIMESTAMP DEFAULT current_timestamp,
  modified TIMESTAMP DEFAULT current_timestamp,
  UNIQUE (organizer_id, exam_language_id, exam_date_id)
);
--;;
CREATE TABLE IF NOT EXISTS exam_session_location (
  id BIGSERIAL PRIMARY KEY,
  street_address TEXT NOT NULL,
  city TEXT NOT NULL,
  other_location_info TEXT NOT NULL,
  extra_information TEXT,
  language_code CHAR(2) REFERENCES language(code) NOT NULL,
  exam_session_id BIGINT NOT NULL REFERENCES exam_session(id) ON DELETE CASCADE,
  created TIMESTAMP DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS participant (
  id BIGSERIAL PRIMARY KEY,
  external_user_id TEXT UNIQUE NOT NULL,
  created TIMESTAMP DEFAULT current_timestamp
);
--;;
CREATE TYPE registration_state AS ENUM ('OK', 'INCOMPLETE', 'ERROR');
--;;
CREATE TABLE IF NOT EXISTS registration (
  id BIGSERIAL PRIMARY KEY,
  state registration_state NOT NULL,
  exam_session_id BIGINT REFERENCES exam_session (id) NOT NULL,
  participant_id BIGINT REFERENCES participant (id) NOT NULL,
  created TIMESTAMP DEFAULT current_timestamp,
  CONSTRAINT one_participation_per_session_constraint UNIQUE (exam_session_id, participant_id)
);
--;;
CREATE TABLE IF NOT EXISTS login_link (
 id BIGSERIAL PRIMARY KEY,
 code TEXT UNIQUE NOT NULL,
 participant_id BIGSERIAL REFERENCES participant (id) NOT NULL,
 exam_session_id BIGSERIAL REFERENCES exam_session (id) NOT NULL,
 expired_link_redirect TEXT NOT NULL,
 success_redirect TEXT NOT NULL,
 expires_at DATE NOT NULL,
 created TIMESTAMP DEFAULT current_timestamp,
 modified TIMESTAMP DEFAULT current_timestamp
);
--;;

