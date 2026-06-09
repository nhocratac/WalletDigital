package com.vng.wallet;

/**
 * A custom exception we throw when someone asks for a wallet id that doesn't exist.
 * Giving errors meaningful names (instead of generic ones) makes code self-documenting
 * and lets us turn them into proper HTTP responses (see GlobalExceptionHandler).
 */
public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException(Long id) {
        super("Wallet not found with id: " + id);
    }
}
