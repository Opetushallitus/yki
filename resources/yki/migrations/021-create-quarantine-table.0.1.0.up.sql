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
    updated       TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);

CREATE TABLE IF NOT EXISTS quarantine_review
(
    id              BIGSERIAL PRIMARY KEY,
    quarantine_id   BIGINT REFERENCES quarantine (id)   NOT NULL,
    registration_id BIGINT REFERENCES registration (id) NOT NULL,
    quarantined     BOOLEAN                             NOT NULL,
    created         TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
    updated         TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
    reviewer_oid    TEXT                                NOT NULL,
    CONSTRAINT quarantine_review_unique_quarantine_registration_combination UNIQUE (quarantine_id, registration_id)
);
