--;;
CREATE TABLE IF NOT EXISTS evaluation (
  id BIGSERIAL PRIMARY KEY,
  exam_date_id BIGSERIAL NOT NULL REFERENCES exam_date(id),
  exam_date_language_id BIGSERIAL REFERENCES exam_date_language(id) NOT NULL,
  evaluation_start_date DATE NOT NULL,
  evaluation_end_date DATE NOT NULL,
  deleted_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
  UNIQUE (exam_date_language_id)
);

-- Is re-evalution period open
CREATE OR REPLACE FUNCTION evaluation_period_open(id bigint, at_point_in_time timestamptz DEFAULT now()) RETURNS SETOF boolean AS $$
BEGIN
    RETURN QUERY SELECT EXISTS (
                     SELECT eva.id
                       FROM evaluation eva
                      WHERE eva.id = evaluation_period_open.id
                        AND within_dt_range(at_point_in_time, eva.evaluation_start_date, eva.evaluation_end_date)
                 ) as exists;
END;
$$ LANGUAGE plpgsql;


CREATE TABLE IF NOT EXISTS subtest (
  code TEXT PRIMARY KEY,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);

INSERT INTO subtest(code) VALUES ('READING') ON CONFLICT DO NOTHING;
INSERT INTO subtest(code) VALUES ('LISTENING')  ON CONFLICT DO NOTHING;
INSERT INTO subtest(code) VALUES ('WRITING')  ON CONFLICT DO NOTHING;
INSERT INTO subtest(code) VALUES ('SPEAKING') ON CONFLICT DO NOTHING;

CREATE TABLE IF NOT EXISTS evaluation_order (
  id BIGSERIAL PRIMARY KEY,
  evaluation_id BIGSERIAL REFERENCES evaluation (id),
  first_names TEXT NOT NULL,
  last_name TEXT NOT NULL,
  email TEXT,
  birthdate TEXT,
  extra TEXT,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
  deleted_at TIMESTAMP WITH TIME ZONE DEFAULT NULL
);

CREATE TABLE IF NOT EXISTS evaluation_order_subtest (
  id BIGSERIAL PRIMARY KEY,
  evaluation_order_id BIGSERIAL NOT NULL REFERENCES evaluation_order(id) ON DELETE CASCADE,
  subtest TEXT REFERENCES subtest (code) NOT NULL,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
  deleted_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
  UNIQUE (evaluation_order_id, subtest)
);

ALTER TYPE login_link_type ADD VALUE IF NOT EXISTS 'EVALUATION_PAYMENT';

--;;
CREATE SEQUENCE IF NOT EXISTS evaluation_payment_order_number_seq;
--;;
CREATE TABLE IF NOT EXISTS evaluation_payment (
  id BIGSERIAL PRIMARY KEY,
  state payment_state NOT NULL,
  evaluation_order_id BIGSERIAL REFERENCES evaluation_order (id) NOT NULL UNIQUE,
  amount NUMERIC NOT NULL,
  lang CHAR(2) NOT NULL,
  reference_number NUMERIC,
  order_number TEXT NOT NULL UNIQUE,
  external_payment_id TEXT UNIQUE,
  payment_method TEXT,
  payed_at TIMESTAMP WITH TIME ZONE,
  created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
  modified TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);

