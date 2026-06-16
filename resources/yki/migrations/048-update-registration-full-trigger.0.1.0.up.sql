-- Drop the old single-argument version; the new version takes a partial_exam_type parameter.
DROP FUNCTION IF EXISTS select_registration_kind(bigint);

-- Determine if exam session has room for a registration of the given partial exam type,
-- or if the registration should be queued instead.
CREATE OR REPLACE FUNCTION select_registration_kind(eid bigint, pet exam_session_ticket_type) RETURNS text AS $$
DECLARE
    es record;
    rl_sub_type exam_session_ticket_type;
    sw_sub_type exam_session_ticket_type;
    queue_count bigint;
    participants_count bigint;
BEGIN
    SELECT INTO es type, max_participants, max_participants_read_listen, max_participants_speak_write
    FROM exam_session WHERE id = eid;

    -- FULL session: count all registrations against max_participants
    IF es.type = 'FULL' THEN
        SELECT COUNT(*) INTO queue_count
        FROM registration r
        WHERE r.exam_session_id = eid AND r.kind = 'QUEUE' AND r.state IN ('STARTED', 'SUBMITTED');

        SELECT COUNT(*) INTO participants_count
        FROM registration r
        WHERE r.exam_session_id = eid AND r.kind = 'ADMISSION' AND r.state IN ('STARTED', 'SUBMITTED', 'COMPLETED');

        IF queue_count > 0 OR participants_count >= es.max_participants THEN RETURN 'QUEUE'; END IF;
        RETURN 'ADMISSION';
    END IF;

    -- Partial session: determine which sub-types compete in each pool
    IF es.type = 'READ_SPEAK' THEN
        rl_sub_type := 'READ';
        sw_sub_type := 'SPEAK';
    ELSE -- LISTEN_WRITE
        rl_sub_type := 'LISTEN';
        sw_sub_type := 'WRITE';
    END IF;

    -- Check read/listen pool when relevant
    IF pet IN (rl_sub_type, 'ALL_PARTS') THEN
        SELECT COUNT(*) INTO queue_count
        FROM registration r
        WHERE r.exam_session_id = eid AND r.kind = 'QUEUE' AND r.state IN ('STARTED', 'SUBMITTED')
          AND r.partial_exam_type IN ('ALL_PARTS', rl_sub_type);

        SELECT COUNT(*) INTO participants_count
        FROM registration r
        WHERE r.exam_session_id = eid AND r.kind = 'ADMISSION' AND r.state IN ('STARTED', 'SUBMITTED', 'COMPLETED')
          AND r.partial_exam_type IN ('ALL_PARTS', rl_sub_type);

        IF queue_count > 0 OR participants_count >= es.max_participants_read_listen THEN RETURN 'QUEUE'; END IF;
        IF pet = rl_sub_type THEN RETURN 'ADMISSION'; END IF;
    END IF;

    -- Check speak/write pool when relevant
    SELECT COUNT(*) INTO queue_count
    FROM registration r
    WHERE r.exam_session_id = eid AND r.kind = 'QUEUE' AND r.state IN ('STARTED', 'SUBMITTED')
      AND r.partial_exam_type IN ('ALL_PARTS', sw_sub_type);

    SELECT COUNT(*) INTO participants_count
    FROM registration r
    WHERE r.exam_session_id = eid AND r.kind = 'ADMISSION' AND r.state IN ('STARTED', 'SUBMITTED', 'COMPLETED')
      AND r.partial_exam_type IN ('ALL_PARTS', sw_sub_type);

    IF queue_count > 0 OR participants_count >= es.max_participants_speak_write THEN RETURN 'QUEUE'; END IF;
    RETURN 'ADMISSION';
END;
$$ LANGUAGE plpgsql;

-- Recreate trigger; drop first as recommended when updating related functionality.
-- Originally defined in migration 004, updated in 008, 036.
DROP TRIGGER IF EXISTS participant_limit_trigger ON registration;

CREATE OR REPLACE FUNCTION error_if_exceeds_participant_limit() RETURNS TRIGGER AS $$
DECLARE
    actual_kind TEXT := (
        select_registration_kind(NEW.exam_session_id, NEW.partial_exam_type)
    );
BEGIN
    IF NEW.kind = 'QUEUE' AND actual_kind = 'ADMISSION' THEN
        RAISE EXCEPTION 'registration to queue is not available';
    ELSIF NEW.kind = 'ADMISSION' AND actual_kind = 'QUEUE' THEN
        RAISE EXCEPTION 'max_participants of exam_session exceeded.';
    ELSE
        RETURN NEW;
    END IF;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER participant_limit_trigger
    BEFORE INSERT
    ON registration
    FOR EACH ROW
EXECUTE PROCEDURE error_if_exceeds_participant_limit();
