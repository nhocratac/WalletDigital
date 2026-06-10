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

    private String ownerName;

    @Column(precision = 38, scale = 2) // chốt scale tiền tệ tường minh (khớp NUMERIC(38,2) hiện tại)
    private BigDecimal balance;

    // Optimistic lock: Hibernate so sánh version khi UPDATE; lệch -> OptimisticLockException.
    @Version
    private Long version;

    protected WalletEntity() {
    }

    public WalletEntity(Long id, String ownerName, BigDecimal balance, Long version) {
        this.id = id;
        this.ownerName = ownerName;
        this.balance = balance;
        this.version = version;
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

    public Long getVersion() {
        return version;
    }
}
