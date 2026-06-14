package com.vng.wallet.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Ma trận transition của WithdrawalOrder (state machine SP4, design §4).
 * Make illegal states unrepresentable — mọi mũi tên không hợp lệ -> ném.
 */
class WithdrawalOrderTest {

    private static WithdrawalOrder pending() {
        return WithdrawalOrder.create("user-1", 7L, new BigDecimal("30"), "k1", "ref-1");
    }

    private static WithdrawalOrder sent() {
        WithdrawalOrder o = pending();
        o.markSent();
        return o;
    }

    private static WithdrawalOrder settled() {
        WithdrawalOrder o = sent();
        o.markSettled();
        return o;
    }

    @Test
    void newOrder_startsPending() {
        WithdrawalOrder o = pending();
        assertEquals(WithdrawalState.PENDING, o.getState());
        assertEquals("ref-1", o.getBankRef());
        assertEquals("k1", o.getIdempotencyKey());
        assertEquals(0, o.getAttemptCount());
        assertNull(o.getFirstSentAt());
    }

    @Test
    void pending_canMarkSent() {
        var o = pending();
        o.markSent();
        assertEquals(WithdrawalState.SENT, o.getState());
        assertNotNull(o.getFirstSentAt(), "firstSentAt được đặt khi rời PENDING -> SENT");
    }

    @Test
    void pending_canSettleDirectly_whenWorkerSeesBankAlreadySettled() {
        var o = pending();
        o.markSettled();
        assertEquals(WithdrawalState.SETTLED, o.getState());
    }

    @Test
    void pending_canFail_whenBankRejectsOnSend() {
        var o = pending();
        o.markFailed("bad account");
        assertEquals(WithdrawalState.FAILED, o.getState());
    }

    @Test
    void sent_canSettle() {
        var o = sent();
        o.markSettled();
        assertEquals(WithdrawalState.SETTLED, o.getState());
    }

    @Test
    void sent_canFail() {
        var o = sent();
        o.markFailed("bad account");
        assertEquals(WithdrawalState.FAILED, o.getState());
    }

    @Test
    void sent_canStayQueriedAsSent() {
        var o = sent();
        o.markSent(); // query lại — vẫn SENT, hợp lệ
        assertEquals(WithdrawalState.SENT, o.getState());
    }

    @Test
    void settled_cannotFail() {
        var o = settled();
        assertThrows(InvalidWithdrawalTransitionException.class, () -> o.markFailed("x"));
    }

    @Test
    void settled_cannotSettleAgain() {
        var o = settled();
        assertThrows(InvalidWithdrawalTransitionException.class, o::markSettled);
    }

    @Test
    void failed_isTerminal() {
        var o = pending();
        o.markFailed("nope");
        assertThrows(InvalidWithdrawalTransitionException.class, o::markSettled);
        assertThrows(InvalidWithdrawalTransitionException.class, () -> o.markFailed("again"));
    }

    @Test
    void escalatesToManualReviewAfterThreshold() {
        var o = sent();
        for (int i = 0; i < o.getMaxAttempts(); i++) {
            o.recordUnknownAttempt();
        }
        o.escalateIfExhausted();
        assertEquals(WithdrawalState.NEEDS_MANUAL_REVIEW, o.getState());
    }

    @Test
    void doesNotEscalateBeforeThreshold() {
        var o = sent();
        o.recordUnknownAttempt();
        o.escalateIfExhausted();
        assertEquals(WithdrawalState.SENT, o.getState(), "chưa đủ ngưỡng -> vẫn SENT");
    }

    @Test
    void manualReview_canBeResolvedByAdmin() {
        var o = sent();
        for (int i = 0; i < o.getMaxAttempts(); i++) {
            o.recordUnknownAttempt();
        }
        o.escalateIfExhausted();
        o.markSettled(); // admin resolve
        assertEquals(WithdrawalState.SETTLED, o.getState());
    }

    @Test
    void recordUnknownAttempt_incrementsCount() {
        var o = sent();
        o.recordUnknownAttempt();
        o.recordUnknownAttempt();
        assertEquals(2, o.getAttemptCount());
    }
}
