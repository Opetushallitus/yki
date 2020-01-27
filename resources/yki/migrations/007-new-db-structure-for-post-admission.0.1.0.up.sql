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

-- 3) Time range checking queries as functions. This used to be an embedded query, but it's more useful in this form.
-- Base DT range check, normalizing to the Finnish timezone from 10 AM of first day to 4 PM of last day as defined to be
-- the static range limits by law.
CREATE OR REPLACE FUNCTION within_dt_range(tz timestamptz,
                                           after timestamptz,
                                           before timestamptz) RETURNS boolean AS $$
BEGIN
    RETURN (after IS NOT NULL AND before IS NOT NULL)
       AND (date_trunc('day', (after AT TIME ZONE 'Europe/Helsinki')) + time '10:00') <= tz AT TIME ZONE 'Europe/Helsinki'
       AND (date_trunc('day', (before AT TIME ZONE 'Europe/Helsinki')) + time '16:00') > tz AT TIME ZONE 'Europe/Helsinki';
END;
$$ LANGUAGE plpgsql;

-- Is normal registration open for exam session?
CREATE OR REPLACE FUNCTION exam_session_registration_open(exam_date_id bigint,
                                                          at_point_in_time timestamptz DEFAULT now()) RETURNS SETOF boolean AS $$
BEGIN
    RETURN QUERY SELECT EXISTS (
                     SELECT es.id
                       FROM exam_session es
                 INNER JOIN exam_date ed ON es.exam_date_id = ed.id
                      WHERE es.id = exam_session_registration_open.exam_date_id
                        AND within_dt_range(at_point_in_time, ed.registration_start_date, ed.registration_end_date)
                 ) as exists;
END;
$$ LANGUAGE plpgsql;

-- Is post registration open for exam session?
CREATE OR REPLACE FUNCTION exam_session_post_registration_open(exam_date_id bigint,
                                                               at_point_in_time timestamptz DEFAULT now()) RETURNS SETOF boolean AS $$
BEGIN
    RETURN QUERY SELECT EXISTS (
                     SELECT es.id
                       FROM exam_session es
                 INNER JOIN exam_date ed ON es.exam_date_id = ed.id
                      WHERE es.id = exam_session_post_registration_open.exam_date_id
                        AND within_dt_range(at_point_in_time, es.post_admission_start_date, ed.post_admission_end_date)
                 ) as exists;
END;
$$ LANGUAGE plpgsql;

-- Determine which registration phase (=kind) a specific session is at specific time.
CREATE OR REPLACE FUNCTION select_registration_phase(exam_session_id bigint,
                                                     at_point_in_time timestamptz DEFAULT now()) RETURNS text AS $$
DECLARE
    registration_phase record;
BEGIN
    SELECT INTO "registration_phase"
        (SELECT * FROM exam_session_registration_open(exam_session_id, at_point_in_time)) AS admission_active,
        (SELECT * FROM exam_session_post_registration_open(exam_session_id, at_point_in_time)) AS post_admission_active
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
