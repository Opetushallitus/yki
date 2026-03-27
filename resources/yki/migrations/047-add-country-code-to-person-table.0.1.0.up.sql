-- Country code of the person's residence / address
ALTER TABLE person
ADD COLUMN IF NOT EXISTS country_code TEXT;