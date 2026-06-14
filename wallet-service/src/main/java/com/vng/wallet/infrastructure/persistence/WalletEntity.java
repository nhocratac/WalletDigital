package com.vng.wallet.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.math.BigDecimal;

@Entity
@Table(name = "wallet")
public class WalletEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    private String ownerName;

    @Column(precision = 38, scale = 2) // chốt scale tiền tệ tường minh (khớp NUMERIC(38,2) hiện tại)
    private BigDecimal balance;

    // SP4 escrow (E2): phan dang giu cho order PENDING/SENT. available = balance - held (dan xuat, khong luu).
    @Column(precision = 38, scale = 2, nullable = false, columnDefinition = "numeric(38,2) default 0")
    private BigDecimal held;

    // Optimistic lock: Hibernate so sánh version khi UPDATE; lệch -> OptimisticLockException.
    @Version
    private Long version;

    protected WalletEntity() {
    }

    public WalletEntity(Long id, String userId, String ownerName, BigDecimal balance, BigDecimal held, Long version) {
        this.id = id;
        this.userId = userId;
        this.ownerName = ownerName;
        this.balance = balance;
        this.held = held == null ? BigDecimal.ZERO : held;
        this.version = version;
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getHeld() {
        return held;
    }

    public Long getVersion() {
        return version;
    }
}
