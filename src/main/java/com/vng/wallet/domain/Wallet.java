package com.vng.wallet.domain;

import java.math.BigDecimal;

/**
 * Domain model thuần Java — KHÔNG import Spring hay JPA.
 * Đây là lõi nghiệp vụ; có thể copy sang project khác vẫn biên dịch được.
 */
public class Wallet {

    private final Long id;          // null khi ví mới; DB cấp id sau
    private final String ownerName;
    private BigDecimal balance;

    public Wallet(Long id, String ownerName, BigDecimal balance) {
        this.id = id;
        this.ownerName = ownerName;
        this.balance = balance;
    }

    /** Tạo ví mới: chưa có id, số dư = 0 (quy tắc nghiệp vụ). */
    public static Wallet createNew(String ownerName) {
        return new Wallet(null, ownerName, BigDecimal.ZERO);
    }

    public Long getId() {
        return id;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public BigDecimal getBalance() {
        return balance;
    }
}
