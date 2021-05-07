-- Drops a constraint that does not appear locally but exists in the cloud

ALTER TABLE exam_date_language
DROP CONSTRAINT IF EXISTS unique_exam_date_id_language_code;
