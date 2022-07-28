CREATE TABLE IF NOT EXISTS exam_payment_new
(
    id              BIGSERIAL PRIMARY KEY,
    state           payment_state                          NOT NULL,
    registration_id BIGSERIAL REFERENCES registration (id) NOT NULL,
    amount          NUMERIC                                NOT NULL,
    reference       TEXT                                   NOT NULL UNIQUE,
    transaction_id  TEXT                                   NOT NULL UNIQUE,
    href            TEXT                                   NOT NULL UNIQUE,
    paid_at         TIMESTAMP WITH TIME ZONE,
    created         TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
    updated         TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);

-- TODO Create a table as well for evaluation payments.
