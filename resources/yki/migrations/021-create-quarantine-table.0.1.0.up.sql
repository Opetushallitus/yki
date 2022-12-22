CREATE TABLE IF NOT EXISTS quarantine
(
    id            BIGSERIAL PRIMARY KEY,
    language_code CHAR(3) REFERENCES language (code) NOT NULL,
    end_date      DATE                               NOT NULL,
    birthdate     TEXT                               NOT NULL,
    first_name    TEXT                               NOT NULL,
    last_name     TEXT                               NOT NULL,
    ssn           TEXT,
    email         TEXT,
    phone_number  TEXT,
    created       TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
    updated       TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
    deleted       TIMESTAMP WITH TIME ZONE
);

ALTER TABLE registration
    ADD COLUMN IF NOT EXISTS quarantine_id BIGINT REFERENCES quarantine (id) DEFAULT NULL;
ALTER TABLE registration
    ADD COLUMN IF NOT EXISTS reviewed TIMESTAMP WITH TIME ZONE DEFAULT NULL;