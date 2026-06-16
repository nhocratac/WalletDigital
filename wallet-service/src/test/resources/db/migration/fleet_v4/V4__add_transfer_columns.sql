-- SP6 (TR1): double-entry transfer. transfer_id groups the TRANSFER_OUT/TRANSFER_IN pair.
-- Portable across H2 (slice tests) + MySQL (integration): plain ADD COLUMN works on both.
ALTER TABLE wallet_transaction ADD COLUMN transfer_id VARCHAR(64);

-- SP6 (TR7): the idempotency_key lives on the TRANSFER_OUT leg only; the TRANSFER_IN leg
-- carries idempotency_key = NULL. The column was already declared nullable in V2
-- (VARCHAR(255) with no NOT NULL), and the UNIQUE constraint uk_wt_idempotency_key already
-- permits multiple NULLs on both H2 and MySQL — so no MODIFY is needed. This comment is the
-- explicit record of that decision (avoids non-portable H2 SET NULL vs MySQL MODIFY syntax).
