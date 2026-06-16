package com.vng.wallet.infrastructure.web.dto;

import com.vng.wallet.application.WalletService.TransferResult;
import java.math.BigDecimal;

/**
 * Kết quả transfer (SP6): nhóm double-entry qua transferId, kèm ví gửi/nhận + số tiền.
 * Replay trả LẠI transfer gốc (cùng transferId) — không chuyển tiền lần hai.
 */
public record TransferResponse(String transferId, Long from, Long to, BigDecimal amount) {
    public static TransferResponse from(TransferResult result) {
        return new TransferResponse(result.transferId(), result.fromWalletId(),
                result.toWalletId(), result.amount());
    }
}
