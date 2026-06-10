package com.vng.wallet.domain;

import java.math.BigDecimal;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(Long walletId, BigDecimal balance, BigDecimal requested) {
        super("Insufficient funds in wallet " + walletId
                + ": balance=" + balance + ", requested=" + requested);
    }
}
