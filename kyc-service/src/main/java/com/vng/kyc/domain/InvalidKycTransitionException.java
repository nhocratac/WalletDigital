package com.vng.kyc.domain;

public class InvalidKycTransitionException extends RuntimeException {
    public InvalidKycTransitionException(KycStatus from, String action) {
        super("Cannot " + action + " from status " + from);
    }
}
