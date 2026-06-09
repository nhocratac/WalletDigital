package com.vng.wallet.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * The data a client must send to create a wallet (the request BODY of POST /wallets).
 *
 * This is a Java "record" — a short way to declare an immutable data holder.
 * @NotBlank means: reject the request automatically if ownerName is missing/empty.
 *
 * ARCHITECT NOTE: why not just accept a Wallet entity directly?
 * Because the API and the database should be DECOUPLED. A client should not
 * be able to set the id or balance directly when creating a wallet. DTOs let us
 * control exactly what the outside world is allowed to send us. This separation
 * is one of the most important habits in service design.
 */
public record CreateWalletRequest(
        @NotBlank(message = "ownerName must not be empty")
        String ownerName
) {
}
