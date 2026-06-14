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
    /**
     * SP4 (E4): vòng đời rút = 3 sự kiện ledger thay cho WITHDRAW trần.
     * Task 3 đã bỏ {@code WITHDRAW} — withdraw giờ order-based: ① ghi WITHDRAW_HOLD,
     * ③ ghi WITHDRAW_SETTLED (tiền rời hệ) hoặc WITHDRAW_REFUNDED (hoàn về available).
     */
    public enum Type { TOPUP, WITHDRAW_HOLD, WITHDRAW_SETTLED, WITHDRAW_REFUNDED }
}
