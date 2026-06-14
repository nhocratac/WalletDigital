package com.vng.wallet.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Aggregate vòng đời rút (E1). State machine + transition guard (như KycCase SP2).
 * "make illegal states unrepresentable" — mọi transition không vẽ trong design §4 đều ném.
 *
 * <p>Tiền chỉ đổi qua MỘT cửa nguyên tử (WithdrawalSettlementService.applyTerminal, Task 4)
 * gated bởi {@code version} (optimistic lock) → exactly-once terminal transition.
 *
 * <p>version: optimistic lock do persistence quản lý (null khi order mới).
 */
public class WithdrawalOrder {

    /** Ngưỡng N: quá số lần query UNKNOWN -> NEEDS_MANUAL_REVIEW (E10). */
    public static final int MAX_ATTEMPTS = 5;
    /** Ngưỡng T: quá thời gian kể từ firstSentAt vẫn UNKNOWN -> NEEDS_MANUAL_REVIEW (E10). */
    public static final Duration MAX_AGE = Duration.ofHours(1);

    private final Long id;
    private final String userId;
    private final Long walletId;
    private final BigDecimal amount;
    private WithdrawalState state;
    private final String bankRef;        // E7: idempotency key tới bank, sinh ở bước ①
    private final String idempotencyKey; // E7: Idempotency-Key của user (UNIQUE)
    private int attemptCount;
    private Instant firstSentAt;
    private final Long version;

    public WithdrawalOrder(Long id, String userId, Long walletId, BigDecimal amount,
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

    /** Tạo lệnh mới ở PENDING — tiền đã vào escrow (① do WalletService thực hiện cùng tx). */
    public static WithdrawalOrder create(String userId, Long walletId, BigDecimal amount,
                                         String idempotencyKey, String bankRef) {
        return new WithdrawalOrder(null, userId, walletId, amount, WithdrawalState.PENDING,
                bankRef, idempotencyKey, 0, null, null);
    }

    /** ② đã gọi bank (hoặc query lại): PENDING|SENT -> SENT. firstSentAt đặt lần đầu rời PENDING. */
    public void markSent() {
        requireFrom(WithdrawalState.SENT, WithdrawalState.PENDING, WithdrawalState.SENT);
        if (firstSentAt == null) {
            firstSentAt = Instant.now();
        }
        this.state = WithdrawalState.SENT;
    }

    /** ③ settle: bank xác nhận đã chuyển. PENDING|SENT|NEEDS_MANUAL_REVIEW -> SETTLED. */
    public void markSettled() {
        requireFrom(WithdrawalState.SETTLED,
                WithdrawalState.PENDING, WithdrawalState.SENT, WithdrawalState.NEEDS_MANUAL_REVIEW);
        this.state = WithdrawalState.SETTLED;
    }

    /** ③ refund: bank từ chối DỨT KHOÁT. PENDING|SENT|NEEDS_MANUAL_REVIEW -> FAILED. */
    public void markFailed(String reason) {
        requireFrom(WithdrawalState.FAILED,
                WithdrawalState.PENDING, WithdrawalState.SENT, WithdrawalState.NEEDS_MANUAL_REVIEW);
        this.state = WithdrawalState.FAILED;
    }

    /** UNKNOWN/timeout: KHÔNG đổi state (E9), chỉ đếm để áp ngưỡng. */
    public void recordUnknownAttempt() {
        this.attemptCount++;
    }

    /** Quá ngưỡng N lần / T giờ mà vẫn SENT -> NEEDS_MANUAL_REVIEW (E10). KHÔNG auto refund/settle. */
    public void escalateIfExhausted() {
        if (state != WithdrawalState.SENT) {
            return;
        }
        boolean tooMany = attemptCount >= MAX_ATTEMPTS;
        boolean tooOld = firstSentAt != null
                && Duration.between(firstSentAt, Instant.now()).compareTo(MAX_AGE) > 0;
        if (tooMany || tooOld) {
            this.state = WithdrawalState.NEEDS_MANUAL_REVIEW;
        }
    }

    private void requireFrom(WithdrawalState to, WithdrawalState... allowed) {
        for (WithdrawalState s : allowed) {
            if (state == s) {
                return;
            }
        }
        throw new InvalidWithdrawalTransitionException(state, to);
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
    public int getMaxAttempts() { return MAX_ATTEMPTS; }
}
