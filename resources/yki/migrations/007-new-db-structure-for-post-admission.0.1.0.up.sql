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
ALTER TABLE exam_session ADD COLUMN IF NOT EXISTS post_admission_active BOOLEAN NULL;
ALTER TABLE exam_session ADD COLUMN IF NOT EXISTS post_admission_quota BOOLEAN NULL;
ALTER TABLE exam_date ADD COLUMN IF NOT EXISTS post_admission_end_date DATE NULL;

CREATE TYPE registration_kind AS ENUM ('ADMISSION', 'POST_ADMISSION', 'OTHER');
ALTER TABLE registration ADD COLUMN IF NOT EXISTS kind "registration_kind" NOT NULL DEFAULT 'ADMISSION';

-- 3) helper function for determining which registration phase (=kind) a specific session is at specific time
CREATE OR REPLACE FUNCTION select_registration_phase(exam_session_id bigint,
                                                     at_point_in_time date DEFAULT now()) RETURNS text AS $$
DECLARE
    registration_phase record;
BEGIN
    SELECT INTO "registration_phase"
        (ed.registration_start_date <= at_point_in_time AND ed.registration_end_date >= at_point_in_time) AS admission_active,
        ((es.post_admission_start_date IS NOT NULL AND ed.post_admission_end_date IS NOT NULL) AND
         (es.post_admission_start_date <= at_point_in_time AND ed.post_admission_end_date >= at_point_in_time)) AS post_admission_active
      FROM "exam_session" es
 LEFT JOIN "exam_date" "ed" ON es."exam_date_id" = "ed"."id"
     WHERE es."id" = exam_session_id;

    IF registration_phase.admission_active THEN
        RETURN 'ADMISSION';
    ELSIF registration_phase."post_admission_active" THEN
        RETURN 'POST_ADMISSION';
    ELSE
        RETURN 'OTHER';
    END IF;
END;
$$ LANGUAGE plpgsql;
