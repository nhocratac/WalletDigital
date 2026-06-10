package com.vng.wallet.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Một bút toán trong sổ cái — BẤT BIẾN. balanceAfter = số dư SAU bút toán (đối soát). */
public record WalletTransaction(
        Long id,                 // null trước khi lưu, DB cấp
        Long walletId,
        Type type,
        BigDecimal amount,
        String idempotencyKey,
        BigDecimal balanceAfter,
        Instant createdAt
) {
    public enum Type { TOPUP, WITHDRAW }
}
