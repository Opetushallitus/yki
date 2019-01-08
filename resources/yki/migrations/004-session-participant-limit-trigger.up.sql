CREATE OR REPLACE FUNCTION error_if_exceeds_participant_limit() RETURNS TRIGGER AS $$
DECLARE
  current_registrations NUMERIC := (
    SELECT count(*) FROM registration
    WHERE exam_session_id = NEW.exam_session_id
    AND state IN ('COMPLETED', 'SUBMITTED', 'STARTED')
  );
  session_limit NUMERIC := (
    SELECT max_participants FROM exam_session WHERE id = NEW.exam_session_id
  );
BEGIN
  IF current_registrations < session_limit THEN
    RETURN NEW;
  ELSE
    RAISE EXCEPTION 'max_participants of exam_session exceeded.';
  END IF;
END;
$$ LANGUAGE plpgsql;
--;;
CREATE TRIGGER participant_limit_trigger
  BEFORE INSERT
  ON registration
  FOR EACH ROW
  EXECUTE PROCEDURE error_if_exceeds_participant_limit();
