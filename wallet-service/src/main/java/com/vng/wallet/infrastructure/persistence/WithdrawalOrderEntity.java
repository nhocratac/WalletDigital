package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.WithdrawalState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Bảng nguồn-sự-thật vòng đời rút (design §8). idempotency_key + bank_ref UNIQUE (E7).
 * {@code version} optimistic lock chống 2 worker / race worker×webhook×admin (exactly-once).
 */
@Entity
@Table(name = "withdrawal_order",
       uniqueConstraints = {
           @UniqueConstraint(name = "uk_wo_idempotency_key", columnNames = "idempotencyKey"),
           @UniqueConstraint(name = "uk_wo_bank_ref", columnNames = "bankRef")
       })
public class WithdrawalOrderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private String userId;

    @Column(name = "wallet_id", nullable = false)
    private Long walletId;

    @Column(precision = 38, scale = 2, nullable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WithdrawalState state;

    @Column(nullable = false)
    private String bankRef;

    @Column(nullable = false)
    private String idempotencyKey;

    @Column(nullable = false)
    private int attemptCount;

    private Instant firstSentAt;

    @Version
    private Long version;

    protected WithdrawalOrderEntity() {
    }

    public WithdrawalOrderEntity(Long id, String userId, Long walletId, BigDecimal amount,
                                 WithdrawalState state, String bankRef, String idempotencyKey,
                                 int attemptCount, Instant firstSentAt, Long version) {
        this.id = id;
        this.userId = userId;
        this.walletId = walletId;
        this.amount = amount;
        this.state = state;
        this.bankRef = bankRef;
        this.idempotencyKey = idempotencyKey;
        this.attemptCount = attemptCount;
        this.firstSentAt = firstSentAt;
        this.version = version;
    }

    public Long getId() { return id; }
    public String getUserId() { return userId; }
    public Long getWalletId() { return walletId; }
    public BigDecimal getAmount() { return amount; }
    public WithdrawalState getState() { return state; }
    public String getBankRef() { return bankRef; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public int getAttemptCount() { return attemptCount; }
    public Instant getFirstSentAt() { return firstSentAt; }
    public Long getVersion() { return version; }
}
