-- Ledger of wallet movements. idempotency_key UNIQUE = DB-level idempotency guard.
CREATE TABLE wallet_transaction (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    wallet_id       BIGINT,
    type            VARCHAR(255),
    amount          NUMERIC(38, 2),
    idempotency_key VARCHAR(255),
    balance_after   NUMERIC(38, 2),
    created_at      TIMESTAMP,
    CONSTRAINT uk_wt_idempotency_key UNIQUE (idempotency_key)
);
