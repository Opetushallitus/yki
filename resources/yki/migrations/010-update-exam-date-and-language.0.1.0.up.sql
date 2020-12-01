ALTER TABLE exam_date
DROP CONSTRAINT exam_date_exam_date_registration_start_date_registration_en_key;

ALTER TABLE exam_date
ADD COLUMN IF NOT EXISTS post_admission_start_date DATE DEFAULT NULL,
ADD COLUMN IF NOT EXISTS post_admission_enabled BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE DEFAULT NULL;

ALTER TABLE exam_date_language
DROP CONSTRAINT exam_date_language_exam_date_id_language_code_key;

ALTER TABLE exam_date_language ADD COLUMN IF NOT EXISTS level_code TEXT REFERENCES exam_level (code) DEFAULT 'PERUS';
ALTER TABLE exam_date_language ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP WITH TIME ZONE DEFAULT NULL;
