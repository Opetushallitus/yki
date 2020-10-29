-- It is recommended to drop existing trigger entirely and recreate it from scratch when updating the related functionality.
-- This was originally defined in migration 004.
DROP TRIGGER IF EXISTS participant_limit_trigger
    ON registration;

CREATE OR REPLACE FUNCTION error_if_exceeds_participant_limit() RETURNS TRIGGER AS $$
DECLARE
    current_registrations NUMERIC := (
        SELECT count(id) FROM registration
         WHERE exam_session_id = NEW.exam_session_id
           AND state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
           AND kind = 'ADMISSION'
    );
    current_post_registrations NUMERIC := (
        SELECT count(id) FROM registration
         WHERE exam_session_id = NEW.exam_session_id
           AND state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
           AND kind = 'POST_ADMISSION'
    );
    session_limit NUMERIC := (
        SELECT max_participants FROM exam_session WHERE id = NEW.exam_session_id
    );
    post_session_limit NUMERIC := (
        SELECT post_admission_quota FROM exam_session WHERE id = NEW.exam_session_id
    );
    admission_active BOOLEAN := (SELECT * FROM exam_session_registration_open(NEW.exam_session_id));
    post_admission_active BOOLEAN := (SELECT * FROM exam_session_post_registration_open(NEW.exam_session_id));
BEGIN
    IF admission_active THEN
        IF current_registrations < session_limit THEN
            RETURN NEW;
        ELSE
            RAISE EXCEPTION 'max_participants of exam_session exceeded.';
        END IF;
    ELSIF post_admission_active THEN
        IF current_post_registrations < COALESCE(post_session_limit, 0) THEN
            RETURN NEW;
        ELSE
            RAISE EXCEPTION 'post admission quota of exam_session exceeded.';
        END IF;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER participant_limit_trigger
    BEFORE INSERT
    ON registration
    FOR EACH ROW
EXECUTE PROCEDURE error_if_exceeds_participant_limit();
