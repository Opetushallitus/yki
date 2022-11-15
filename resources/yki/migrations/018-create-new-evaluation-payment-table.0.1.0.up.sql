CREATE TABLE IF NOT EXISTS evaluation_payment_new
(
    id                  BIGSERIAL PRIMARY KEY,
    state               payment_state                              NOT NULL,
    evaluation_order_id BIGSERIAL REFERENCES evaluation_order (id) NOT NULL UNIQUE,
    amount              NUMERIC                                    NOT NULL,
    reference           TEXT                                       NOT NULL UNIQUE,
    transaction_id      TEXT                                       NOT NULL UNIQUE,
    href                TEXT                                       NOT NULL UNIQUE,
    paid_at             TIMESTAMP WITH TIME ZONE,
    created             TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp,
    updated             TIMESTAMP WITH TIME ZONE DEFAULT current_timestamp
);

CREATE INDEX IF NOT EXISTS evaluation_payment_new_state ON evaluation_payment_new (state);
