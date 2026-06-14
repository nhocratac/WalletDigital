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
    private BigDecimal balance;    // total — tổng còn sở hữu (gồm cả tiền đang chờ rút)
    private BigDecimal held;       // SP4 escrow: phần đang giữ cho order PENDING/SENT
    private final Long version;

    public Wallet(Long id, String userId, String ownerName, BigDecimal balance, BigDecimal held, Long version) {
        this.id = id;
        this.userId = userId;
        this.ownerName = ownerName;
        this.balance = balance;
        this.held = held == null ? BigDecimal.ZERO : held;
        this.version = version;
    }

    public static Wallet createNew(String userId, String ownerName) {
        return new Wallet(null, userId, ownerName, BigDecimal.ZERO, BigDecimal.ZERO, null);
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

    /** available = balance − held — số mà lệnh rút MỚI phải soi (E3). */
    public BigDecimal available() {
        return balance.subtract(held);
    }

    /** ① hold: ví→escrow. held += amount; balance không đổi (tiền vẫn sở hữu, chỉ bị giữ). */
    public void reserve(BigDecimal amount) {
        requirePositive(amount);
        if (amount.compareTo(available()) > 0) {
            throw new InsufficientFundsException(id, available(), amount);
        }
        this.held = this.held.add(amount);
    }

    /** ③ settle: tiền rời hệ. balance −= amount; held −= amount (available không đổi). */
    public void settle(BigDecimal amount) {
        this.held = this.held.subtract(amount);
        this.balance = this.balance.subtract(amount);
    }

    /** ③ refund: trả hold về available. held −= amount (balance không đổi). */
    public void release(BigDecimal amount) {
        this.held = this.held.subtract(amount);
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
    public BigDecimal getHeld() { return held; }
    public Long getVersion() { return version; }
}
