package com.vng.wallet.domain;

/**
 * Vòng đời của một lệnh rút (SP4, design §4).
 * SETTLED & FAILED là terminal. NEEDS_MANUAL_REVIEW chờ con người quyết.
 */
public enum WithdrawalState {
    PENDING,
    SENT,
    SETTLED,
    FAILED,
    NEEDS_MANUAL_REVIEW;

    public boolean isTerminal() {
        return this == SETTLED || this == FAILED;
    }
}
