package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.WalletTransaction;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "wallet_transaction",
       uniqueConstraints = @UniqueConstraint(columnNames = "idempotencyKey")) // chốt idempotency tầng DB
public class WalletTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long walletId;
    @Enumerated(EnumType.STRING)
    private WalletTransaction.Type type;
    @Column(precision = 38, scale = 2) // chốt scale tiền tệ tường minh (khớp NUMERIC(38,2) hiện tại)
    private BigDecimal amount;
    private String idempotencyKey;
    @Column(precision = 38, scale = 2)
    private BigDecimal balanceAfter;
    private Instant createdAt;

    protected WalletTransactionEntity() {}

    public WalletTransactionEntity(Long id, Long walletId, WalletTransaction.Type type,
                                   BigDecimal amount, String idempotencyKey,
                                   BigDecimal balanceAfter, Instant createdAt) {
        this.id = id; this.walletId = walletId; this.type = type; this.amount = amount;
        this.idempotencyKey = idempotencyKey; this.balanceAfter = balanceAfter; this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getWalletId() { return walletId; }
    public WalletTransaction.Type getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public Instant getCreatedAt() { return createdAt; }
}
