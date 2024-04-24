CREATE INDEX IF NOT EXISTS exam_session_queue_exam_session_id ON exam_session_queue (exam_session_id);

INSERT INTO task_lock (task, last_executed)
VALUES ('REMOVE_OLD_DATA_HANDLER', '-infinity');
