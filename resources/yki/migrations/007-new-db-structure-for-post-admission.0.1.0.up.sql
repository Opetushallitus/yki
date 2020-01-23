/*
Customer changed specification so drastically that this migration rolls back the 006 migration entirely and makes quite
a bit fewer and less drastic changes.
 */

-- 1) drop previously created unnecessary objects
DROP TABLE IF EXISTS post_admission_participant;
DROP TABLE IF EXISTS post_admission;
DROP FUNCTION IF EXISTS time_passed_since;

-- 2) add new database objects to support the desired functionality
ALTER TABLE exam_session ADD COLUMN IF NOT EXISTS post_admission_start_date DATE NULL;
ALTER TABLE exam_date ADD COLUMN IF NOT EXISTS post_admission_end_date DATE NULL;

CREATE TYPE registration_kind AS ENUM ('ADMISSION', 'POST_ADMISSION', 'EXTERNAL');
ALTER TABLE registration ADD COLUMN IF NOT EXISTS kind "registration_kind" NOT NULL DEFAULT 'ADMISSION';

