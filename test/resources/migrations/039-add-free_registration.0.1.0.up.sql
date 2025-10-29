CREATE TABLE IF NOT EXISTS free_registration (
    free_registration_id BIGSERIAL PRIMARY KEY,
    source VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    approved BOOLEAN,
    comment TEXT,
    matriculation_exam BOOLEAN NOT NULL,
    higher_education_concluded BOOLEAN NOT NULL,
    higher_education_enrolled BOOLEAN NOT NULL,
    eb BOOLEAN NOT NULL,
    dia BOOLEAN NOT NULL,
    other BOOLEAN NOT NULL,
    registration_id BIGINT NOT NULL
);

