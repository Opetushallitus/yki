DO $$ BEGIN
  CREATE TYPE exam_session_type AS ENUM ('FULL','READ_SPEAK','LISTEN_WRITE');
  CREATE TYPE exam_session_ticket_type AS ENUM ('ALL_PARTS','READ','SPEAK','LISTEN','WRITE');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

ALTER TABLE exam_session ADD COLUMN IF NOT EXISTS type exam_session_type NOT NULL DEFAULT 'FULL'::exam_session_type;
ALTER TABLE exam_date ADD COLUMN IF NOT EXISTS type exam_session_type NOT NULL DEFAULT 'FULL'::exam_session_type;
ALTER TABLE registration ADD COLUMN IF NOT EXISTS partial_exam_type exam_session_ticket_type NOT NULL DEFAULT 'ALL_PARTS'::exam_session_ticket_type;
