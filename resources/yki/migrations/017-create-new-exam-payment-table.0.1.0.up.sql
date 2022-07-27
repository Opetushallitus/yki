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

-- TODO Remove index? Should not be needed as we can always check if
--  a registration needs payment by relying on registration.state.
CREATE INDEX IF NOT EXISTS exam_payment_new_registration_id ON exam_payment_new(registration_id);

-- TODO Create a table as well for evaluation payments.
-- TODO Create also a index on evaluation_payment_new.evaluation_id.
