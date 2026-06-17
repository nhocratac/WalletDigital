-- SP7 Bước 1 (L2, expand): tách idempotency RA KHỎI sổ cái. Bảng RIÊNG, no-partition,
-- UNIQUE(key) toàn cục TRONG schema tenant (routed như wallet_transaction — SP5). Reserve-key-FIRST:
-- INSERT (key, fingerprint) TRƯỚC khi chuyển tiền; trùng key -> recovery (replay / 409). created_at
-- để TTL purge (Task 6). Portable H2 (slice) + MySQL (integration): PK = UNIQUE toàn cục, kiểu cơ bản.
CREATE TABLE idempotency_record (
    idempotency_key     VARCHAR(255) PRIMARY KEY,   -- UNIQUE toàn cục (trong schema tenant)
    operation_type      VARCHAR(32)  NOT NULL,       -- TOPUP / WITHDRAW / TRANSFER
    request_fingerprint VARCHAR(64)  NOT NULL,       -- hash payload (same-key-diff-payload -> 409)
    result_ref          VARCHAR(64),                 -- txId/orderId/transferId (null tới khi op xong)
    created_at          TIMESTAMP    NOT NULL         -- để TTL purge
);
