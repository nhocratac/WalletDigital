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
     * WITHDRAW còn lại tạm thời để giữ compile/hành vi đường cũ — Task 3 thay thế trọn
     * khi withdraw chuyển sang order-based (drift có chủ đích).
     */
    public enum Type { TOPUP, WITHDRAW, WITHDRAW_HOLD, WITHDRAW_SETTLED, WITHDRAW_REFUNDED }
}
