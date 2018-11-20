CREATE TABLE IF NOT EXISTS task_lock (
  task TEXT primary key,
  last_executed timestamp with time zone not null,
  worker_id TEXT
);
--;;
INSERT INTO task_lock(task, last_executed)
VALUES ('PAYMENT_STATE_HANDLER', '-infinity');
--;;
INSERT INTO task_lock(task, last_executed)
VALUES ('REGISTRATION_STATE_HANDLER', '-infinity');
--;;
