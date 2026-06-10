package com.vng.wallet.infrastructure.web.dto;

import com.vng.wallet.domain.WalletTransaction;
import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(Long id, Long walletId, String type, BigDecimal amount,
                                  BigDecimal balanceAfter, Instant createdAt) {
    public static TransactionResponse from(WalletTransaction tx) {
        return new TransactionResponse(tx.id(), tx.walletId(), tx.type().name(),
                tx.amount(), tx.balanceAfter(), tx.createdAt());
    }
}
