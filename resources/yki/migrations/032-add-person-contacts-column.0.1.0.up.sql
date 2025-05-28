CREATE TABLE IF NOT EXISTS person (
    oid TEXT PRIMARY KEY,
    first_name TEXT NOT NULL,
    last_name TEXT NOT NULL,
    email TEXT,
    created TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
    modified TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);
