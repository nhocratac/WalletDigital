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
        Instant createdAt,
        String transferId       // SP6 (TR1): nhóm cặp double-entry TRANSFER_OUT/IN; null cho topup/withdraw
) {
    /**
     * SP4 (E4): vòng đời rút = 3 sự kiện ledger thay cho WITHDRAW trần.
     * Task 3 đã bỏ {@code WITHDRAW} — withdraw giờ order-based: ① ghi WITHDRAW_HOLD,
     * ③ ghi WITHDRAW_SETTLED (tiền rời hệ) hoặc WITHDRAW_REFUNDED (hoàn về available).
     * SP6 (TR1): + TRANSFER_OUT (trừ ví gửi) / TRANSFER_IN (cộng ví nhận), cùng transferId.
     */
    public enum Type { TOPUP, WITHDRAW_HOLD, WITHDRAW_SETTLED, WITHDRAW_REFUNDED, TRANSFER_OUT, TRANSFER_IN }

    /**
     * Compact 7-arg constructor giữ NGUYÊN hợp đồng cũ (topup/withdraw/ledger không-transfer):
     * transferId mặc định null. Mọi caller SP1–SP5 không phải đổi.
     */
    public WalletTransaction(Long id, Long walletId, Type type, BigDecimal amount,
                             String idempotencyKey, BigDecimal balanceAfter, Instant createdAt) {
        this(id, walletId, type, amount, idempotencyKey, balanceAfter, createdAt, null);
    }
}
