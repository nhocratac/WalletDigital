package com.vng.wallet.domain;

/**
 * Transition bất hợp lệ của WithdrawalOrder (vd SETTLED -> FAILED).
 * Như InvalidKycTransitionException của SP2 — make illegal states unrepresentable.
 */
public class InvalidWithdrawalTransitionException extends RuntimeException {
    public InvalidWithdrawalTransitionException(WithdrawalState from, WithdrawalState to) {
        super("Invalid withdrawal transition: " + from + " -> " + to);
    }
}
