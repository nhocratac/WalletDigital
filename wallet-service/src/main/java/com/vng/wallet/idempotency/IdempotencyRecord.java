package com.vng.wallet.idempotency;

import java.time.Instant;

/**
 * SP7 Bước 1 (L2): bản ghi idempotency — lá chắn dedup tách RA KHỎI sổ cái. BẤT BIẾN.
 *
 * <p>{@code idempotencyKey} là UNIQUE toàn cục trong schema tenant (reserve-key-FIRST claim qua
 * UNIQUE). {@code requestFingerprint} = hash ổn định của payload để phát hiện same-key-different
 * payload → 409. {@code resultRef} trỏ kết quả (txId/orderId/transferId) để replay trả đúng cái cũ;
 * null tới khi money op xong (claim trước, complete sau). {@code createdAt} để TTL purge.
 */
public record IdempotencyRecord(
        String idempotencyKey,
        String operationType,
        String requestFingerprint,
        String resultRef,
        Instant createdAt
) {
}
