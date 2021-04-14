--;;
CREATE TABLE IF NOT EXISTS evaluation_period (
  id BIGSERIAL PRIMARY KEY,
  language_code CHAR(3) REFERENCES language (code) NOT NULL,
  level_code TEXT REFERENCES exam_level (code) NOT NULL,
  exam_date_id BIGSERIAL NOT NULL REFERENCES exam_date(id),
  evaluation_start_date DATE NOT NULL,
  evaluation_end_date DATE NOT NULL,
  deleted_at TIMESTAMP WITH TIME ZONE DEFAULT NULL,
  UNIQUE (language_code, level_code, exam_date_id)
);

-- Is re-evalution period open
CREATE OR REPLACE FUNCTION evaluation_period_open(id bigint, at_point_in_time timestamptz DEFAULT now()) RETURNS SETOF boolean AS $$
BEGIN
    RETURN QUERY SELECT EXISTS (
                     SELECT rep.id
                       FROM evaluation_period rep
                      WHERE rep.id = evaluation_period_open.id
                        AND within_dt_range(at_point_in_time, rep.evaluation_start_date, rep.evaluation_end_date)
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

