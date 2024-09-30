CREATE TABLE IF NOT EXISTS participant_onr (
    oid TEXT PRIMARY KEY,
    participant_id BIGSERIAL REFERENCES participant(id) NOT NULL,
    oppijanumero TEXT,
    is_individualized BOOLEAN,
    modified TIMESTAMP WITH TIME ZONE default current_timestamp
);

CREATE INDEX IF NOT EXISTS participant_onr_participant_id ON participant_onr (participant_id);
CREATE INDEX IF NOT EXISTS participant_onr_is_individualized ON participant_onr (is_individualized);
CREATE INDEX IF NOT EXISTS participant_onr_oppijanumero ON participant_onr (oppijanumero);
CREATE INDEX IF NOT EXISTS participant_onr_modified ON participant_onr (modified);
