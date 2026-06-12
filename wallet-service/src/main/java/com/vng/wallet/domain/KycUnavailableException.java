package com.vng.wallet.domain;

public class KycUnavailableException extends RuntimeException {
    public KycUnavailableException() { super("KYC verification temporarily unavailable"); }
}
