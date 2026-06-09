package com.vng.wallet.dto;

import com.vng.wallet.Wallet;

import java.math.BigDecimal;

/**
 * The data we send BACK to the client. Again separate from the Wallet entity,
 * so internal database details never leak out of our API by accident.
 *
 * The static `from` method converts a Wallet (DB object) into a WalletResponse
 * (API object). This little conversion step is your control point.
 */
public record WalletResponse(
        Long id,
        String ownerName,
        BigDecimal balance
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.getId(),
                wallet.getOwnerName(),
                wallet.getBalance()
        );
    }
}
