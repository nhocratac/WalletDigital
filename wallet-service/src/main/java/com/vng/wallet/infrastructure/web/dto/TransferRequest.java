package com.vng.wallet.infrastructure.web.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/**
 * Body của POST /wallets/{fromId}/transfer (SP6). Chỉ chứa ví NHẬN + số tiền —
 * fromId lấy từ path, caller từ header X-User-Id (KHÔNG đọc từ body, chống IDOR / D2).
 */
public record TransferRequest(
        @NotNull(message = "toWalletId must not be null")
        Long toWalletId,

        @NotNull
        @Positive(message = "amount must be positive")
        @Digits(integer = 36, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal amount) {}
