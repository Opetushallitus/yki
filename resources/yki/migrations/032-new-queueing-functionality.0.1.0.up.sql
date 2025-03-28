ALTER TYPE registration_kind ADD VALUE IF NOT EXISTS 'QUEUE';

-- Determine if exam session has room for participant or if the registration to be created should be queued instead.
CREATE OR REPLACE FUNCTION select_registration_kind(eid bigint) RETURNS text AS $$
DECLARE
    registration_kind record;
BEGIN
    SELECT INTO "registration_kind"
        (SELECT EXISTS (SELECT 1 FROM registration r WHERE r.exam_session_id = eid AND r.kind = 'QUEUE' AND r.state IN ('STARTED','SUBMITTED'))) AS has_queue,
        (SELECT COUNT(*) FROM registration r WHERE r.exam_session_id = eid AND r.kind = 'ADMISSION' AND r.state IN ('STARTED','SUBMITTED','COMPLETED') HAVING COUNT(*) >= es.max_participants) AS is_full
    FROM "exam_session" es
    WHERE es."id" = eid;

    IF registration_kind.has_queue THEN
        RETURN 'QUEUE';
    ELSIF registration_kind.is_full THEN
        RETURN 'QUEUE';
    ELSE
        RETURN 'ADMISSION';
    END IF;
END;
$$ LANGUAGE plpgsql;

-- It is recommended to drop existing trigger entirely and recreate it from scratch when updating the related functionality.
-- This was originally defined in migration 004 and later updated in migration 008.
DROP TRIGGER IF EXISTS participant_limit_trigger ON registration;

CREATE OR REPLACE FUNCTION error_if_exceeds_participant_limit() RETURNS TRIGGER AS $$
DECLARE
    current_registrations NUMERIC := (
        SELECT count(id) FROM registration
        WHERE exam_session_id = NEW.exam_session_id
          AND state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
          AND kind = 'ADMISSION'
    );
    session_limit NUMERIC := (
        SELECT max_participants FROM exam_session WHERE id = NEW.exam_session_id
    );
    registration_kind TEXT := (
        select_registration_kind(NEW.exam_session_id)
    );
BEGIN
    IF NEW.kind = 'QUEUE' THEN
        IF registration_kind = 'QUEUE' THEN
            RETURN NEW;
        ELSE
            RAISE EXCEPTION 'registration to queue is not available';
        END IF;
    -- TODO Does not currently correctly handle case where NEW.kind = 'ADMISSION' but registration_kind = 'QUEUE'
    --  while current_registration < session_limit holds - ie. someone has enrolled to queue, registration is momentarily not full,
    --  and user currently tries to enroll directly to exam session!
    ELSIF current_registrations < session_limit THEN
        RETURN NEW;
    ELSE
        RAISE EXCEPTION 'max_participants of exam_session exceeded.';
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER participant_limit_trigger
    BEFORE INSERT
    ON registration
    FOR EACH ROW
EXECUTE PROCEDURE error_if_exceeds_participant_limit();
