CREATE TABLE IF NOT EXISTS quarantine
(
    id                  BIGSERIAL PRIMARY KEY,
    language_code       CHAR(3) REFERENCES language (code)     NOT NULL,
    level_code          TEXT REFERENCES exam_level (code)      NOT NULL,
    end_date            DATE                                   NOT NULL,
    birthdate           TEXT                                   NOT NULL,
    ssn                 TEXT,
    name                TEXT,
    email               TEXT,
    phone_number        TEXT,
    created             TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
    updated             TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);

ALTER TABLE registration ADD COLUMN IF NOT EXISTS quarantined BIGINT REFERENCES quarantine(id) DEFAULT NULL;
ALTER TABLE registration ADD COLUMN IF NOT EXISTS reviewed TIMESTAMP WITH TIME ZONE DEFAULT NULL;
