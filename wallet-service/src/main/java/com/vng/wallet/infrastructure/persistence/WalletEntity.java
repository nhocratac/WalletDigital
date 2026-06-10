package com.vng.wallet.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "wallet")
public class WalletEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String ownerName;

    private BigDecimal balance;

    // Optimistic lock — annotation @Version sẽ được gắn ở Task 5.
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
