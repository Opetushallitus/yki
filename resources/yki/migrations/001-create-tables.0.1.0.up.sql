CREATE TABLE IF NOT EXISTS language (
  code CHAR(2) PRIMARY KEY,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS exam_level (
  code TEXT PRIMARY KEY,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
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
  deleted_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
  modified TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);
--;;
CREATE UNIQUE INDEX IF NOT EXISTS organizer_oid
ON organizer(oid)
WHERE deleted_at IS NULL;
--;;
CREATE TABLE IF NOT EXISTS exam_language (
  id BIGSERIAL PRIMARY KEY,
  language_code CHAR(2) REFERENCES language (code) NOT NULL,
  level_code TEXT REFERENCES exam_level (code) NOT NULL,
  organizer_id BIGSERIAL NOT NULL REFERENCES organizer(id),
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS attachment_metadata (
  external_id TEXT PRIMARY KEY,
  organizer_id BIGSERIAL NOT NULL REFERENCES organizer(id),
  type TEXT,
  deleted_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS exam_date (
  id BIGSERIAL PRIMARY KEY,
  exam_date DATE NOT NULL,
  registration_start_date DATE NOT NULL,
  registration_end_date DATE NOT NULL,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
  modified TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
  UNIQUE (exam_date, registration_start_date, registration_end_date)
);
--;;
CREATE TABLE IF NOT EXISTS exam_date_language (
  id BIGSERIAL PRIMARY KEY,
  exam_date_id BIGSERIAL REFERENCES exam_date(id) NOT NULL,
  language_code CHAR(2) REFERENCES language(code) NOT NULL,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS exam_session (
  id BIGSERIAL PRIMARY KEY,
  organizer_id BIGSERIAL NOT NULL REFERENCES organizer(id),
  exam_language_id BIGSERIAL NOT NULL REFERENCES exam_language(id),
  exam_date_id BIGSERIAL NOT NULL REFERENCES exam_date(id),
  max_participants INTEGER NOT NULL,
  published_at TIMESTAMP WITH TIME ZONE  DEFAULT NULL,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
  modified TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
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
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);
--;;
CREATE TABLE IF NOT EXISTS participant (
  id BIGSERIAL PRIMARY KEY,
  external_user_id TEXT UNIQUE NOT NULL,
  email TEXT,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);
--;;
CREATE TYPE registration_state AS ENUM ('COMPLETED', 'SUBMITTED', 'STARTED', 'ERROR');
--;;
CREATE TABLE IF NOT EXISTS registration (
  id BIGSERIAL PRIMARY KEY,
  state registration_state NOT NULL,
  exam_session_id BIGINT REFERENCES exam_session (id) NOT NULL,
  participant_id BIGINT REFERENCES participant (id) NOT NULL,
  started_at TIMESTAMP WITH TIME ZONE,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
  modified TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
  CONSTRAINT one_participation_per_session UNIQUE (exam_session_id, participant_id)
);
--;;
CREATE TYPE login_link_type AS ENUM ('LOGIN_LINK', 'REGISTRATION', 'PAYMENT');
--;;
CREATE TABLE IF NOT EXISTS login_link (
 id BIGSERIAL PRIMARY KEY,
 code TEXT UNIQUE NOT NULL,
 participant_id BIGSERIAL REFERENCES participant (id) NOT NULL,
 exam_session_id BIGINT REFERENCES exam_session (id),
 registration_id BIGINT REFERENCES registration (id),
 type login_link_type NOT NULL,
 expired_link_redirect TEXT NOT NULL,
 success_redirect TEXT NOT NULL,
 expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
 created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
 modified TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);
--;;
CREATE TYPE payment_state AS ENUM ('PAID', 'UNPAID', 'ERROR');
--;;
CREATE SEQUENCE IF NOT EXISTS payment_order_number_seq;
--;;
CREATE TABLE IF NOT EXISTS payment (
  id BIGSERIAL PRIMARY KEY,
  state payment_state NOT NULL,
  registration_id BIGSERIAL REFERENCES registration (id) NOT NULL UNIQUE,
  amount NUMERIC NOT NULL,
  lang TEXT NOT NULL,
  reference_number NUMERIC,
  order_number TEXT NOT NULL UNIQUE,
  external_payment_id TEXT UNIQUE,
  payment_method TEXT,
  payed_at TIMESTAMP WITH TIME ZONE,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
  modified TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);
--;;
create table cas_ticketstore (
  ticket TEXT PRIMARY KEY,
  logged_in TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);
--;;
