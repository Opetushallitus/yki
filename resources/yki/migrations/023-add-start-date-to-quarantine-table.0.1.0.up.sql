ALTER TABLE quarantine ADD COLUMN IF NOT EXISTS start_date DATE;

UPDATE quarantine SET start_date = created;

ALTER TABLE quarantine ALTER COLUMN start_date SET NOT NULL;

ALTER TABLE quarantine ADD CONSTRAINT ck_quarantine_start_date_end_date CHECK (start_date < end_date);
