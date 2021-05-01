ALTER TABLE evaluation
ADD COLUMN IF NOT EXISTS exam_date_language_id BIGSERIAL REFERENCES exam_date_language(id) NOT NULL,
ADD COLUMN IF NOT EXISTS exam_date_id BIGSERIAL REFERENCES exam_date(id) NOT NULL,
DROP COLUMN IF EXISTS language_code,
DROP COLUMN IF EXISTS level_code;
