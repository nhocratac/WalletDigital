package com.vng.wallet.infrastructure.web.dto;

import com.vng.wallet.domain.Wallet;

import java.math.BigDecimal;

public record WalletResponse(
        Long id,
        String ownerName,
        BigDecimal balance
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.getId(), wallet.getOwnerName(), wallet.getBalance());
    }
}
