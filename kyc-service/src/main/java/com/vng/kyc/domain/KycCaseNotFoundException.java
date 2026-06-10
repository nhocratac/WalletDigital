package com.vng.kyc.domain;

public class KycCaseNotFoundException extends RuntimeException {
    public KycCaseNotFoundException(String userId) {
        super("KYC case not found for user: " + userId);
    }
}
