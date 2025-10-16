-- Gender code as used with SOLKI
-- M for male, N for female, E for not known / other
CREATE TYPE gender_code AS ENUM ('M', 'N', 'E');

ALTER TABLE person
    ADD COLUMN IF NOT EXISTS nationality_code TEXT,
    ADD COLUMN IF NOT EXISTS gender gender_code;
