CREATE TABLE IF NOT EXISTS registration_evaluation (
    registration_evaluation_id BIGSERIAL PRIMARY KEY,
    registration_id BIGINT REFERENCES registration (id) NOT NULL UNIQUE,
    state TEXT NOT NULL,
    version INTEGER DEFAULT 0 NOT NULL,
    created_by TEXT,
    modified_by TEXT,
    deleted_by TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
    modified_at TIMESTAMP WITH TIME ZONE DEFAULT now() NOT NULL,
    deleted_at TIMESTAMP WITH TIME ZONE
);
