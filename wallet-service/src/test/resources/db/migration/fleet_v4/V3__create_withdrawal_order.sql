-- Source-of-truth for withdrawal lifecycle (SP4). idempotency_key + bank_ref UNIQUE (E7);
-- version = optimistic lock (exactly-once across worker / webhook / admin).
CREATE TABLE withdrawal_order (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id         VARCHAR(255)   NOT NULL,
    wallet_id       BIGINT         NOT NULL,
    amount          NUMERIC(38, 2) NOT NULL,
    state           VARCHAR(255)   NOT NULL,
    bank_ref        VARCHAR(255)   NOT NULL,
    idempotency_key VARCHAR(255)   NOT NULL,
    attempt_count   INTEGER        NOT NULL,
    first_sent_at   TIMESTAMP,
    version         BIGINT,
    CONSTRAINT uk_wo_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT uk_wo_bank_ref UNIQUE (bank_ref)
);
