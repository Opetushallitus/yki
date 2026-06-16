ALTER TABLE exam_session
    ADD COLUMN IF NOT EXISTS max_participants_read_listen integer,
    ADD COLUMN IF NOT EXISTS max_participants_speak_write integer,
    ADD COLUMN IF NOT EXISTS start_time text,
    ADD COLUMN IF NOT EXISTS start_time_read_listen text,
    ADD COLUMN IF NOT EXISTS start_time_speak_write text;
