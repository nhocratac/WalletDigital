package com.vng.wallet.domain;

public class KycNotApprovedException extends RuntimeException {
    private final String kycStatus;
    public KycNotApprovedException(String kycStatus) {
        super("KYC approval required"); this.kycStatus = kycStatus;
    }
    public String getKycStatus() { return kycStatus; }
}
