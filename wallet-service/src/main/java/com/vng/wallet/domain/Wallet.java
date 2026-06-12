package com.vng.wallet.domain;

import java.math.BigDecimal;

/**
 * Domain model thuần Java. Quy tắc tiền tệ sống TẠI ĐÂY:
 * - amount phải > 0
 * - không rút quá số dư (InsufficientFundsException)
 * version: optimistic lock, do persistence quản lý (null khi ví mới).
 */
public class Wallet {

    private final Long id;
    private final String userId;   // khoá định danh chủ ví — KHÔNG phải ownerName
    private final String ownerName;
    private BigDecimal balance;
    private final Long version;

    public Wallet(Long id, String userId, String ownerName, BigDecimal balance, Long version) {
        this.id = id;
        this.userId = userId;
        this.ownerName = ownerName;
        this.balance = balance;
        this.version = version;
    }

    public static Wallet createNew(String userId, String ownerName) {
        return new Wallet(null, userId, ownerName, BigDecimal.ZERO, null);
    }

    public void topup(BigDecimal amount) {
        requirePositive(amount);
        this.balance = this.balance.add(amount);
    }

    public void withdraw(BigDecimal amount) {
        requirePositive(amount);
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(id, balance, amount);
        }
        this.balance = this.balance.subtract(amount);
    }

    private void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public String getOwnerName() { return ownerName; }
    public BigDecimal getBalance() { return balance; }
    public Long getVersion() { return version; }
}
