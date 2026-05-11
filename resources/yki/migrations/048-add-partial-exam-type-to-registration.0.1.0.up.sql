DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'exam_session_type') THEN
    CREATE TYPE exam_session_type AS ENUM ('FULL','READ_SPEAK','LISTEN_WRITE');
  END IF;
  IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'exam_session_ticket_type') THEN
    CREATE TYPE exam_session_ticket_type AS ENUM ('ALL_PARTS','READ','SPEAK','LISTEN','WRITE');
  END IF;
END$$;

ALTER TABLE exam_session
  ADD COLUMN IF NOT EXISTS type exam_session_type NOT NULL DEFAULT 'FULL';

ALTER TABLE registration
  ADD COLUMN IF NOT EXISTS partial_exam_type exam_session_ticket_type NOT NULL DEFAULT 'ALL_PARTS';
