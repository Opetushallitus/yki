ALTER TABLE quarantine ADD COLUMN IF NOT EXISTS start_date DATE;

UPDATE quarantine SET start_date = created;

ALTER TABLE quarantine ALTER COLUMN start_date SET NOT NULL;
