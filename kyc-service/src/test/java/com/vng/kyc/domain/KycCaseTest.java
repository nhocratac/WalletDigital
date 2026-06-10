package com.vng.kyc.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KycCaseTest {

    private KycCase caseIn(KycStatus status) {
        // rehydrate constructor: userId, status, currentSubmissionId, version
        return new KycCase("user-1", status, status == KycStatus.NOT_STARTED ? null : "sub-old", 0L);
    }

    // ===== submit() : NOT_STARTED/REJECTED/REVOKED -> PENDING =====
    @Test void submit_fromNotStarted_ok() {
        KycCase c = caseIn(KycStatus.NOT_STARTED);
        c.submit("sub-1");
        assertEquals(KycStatus.PENDING, c.getStatus());
        assertEquals("sub-1", c.getCurrentSubmissionId());
    }
    @Test void submit_fromRejected_ok() {
        KycCase c = caseIn(KycStatus.REJECTED);
        c.submit("sub-2");
        assertEquals(KycStatus.PENDING, c.getStatus());
        assertEquals("sub-2", c.getCurrentSubmissionId());
    }
    @Test void submit_fromRevoked_ok() {
        KycCase c = caseIn(KycStatus.REVOKED);
        c.submit("sub-2");
        assertEquals(KycStatus.PENDING, c.getStatus());
    }
    @Test void submit_fromPending_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.PENDING).submit("s"));
    }
    @Test void submit_fromApproved_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.APPROVED).submit("s"));
    }

    // ===== approve() : CHỈ PENDING -> APPROVED =====
    @Test void approve_fromPending_ok() {
        KycCase c = caseIn(KycStatus.PENDING);
        c.approve();
        assertEquals(KycStatus.APPROVED, c.getStatus());
    }
    @Test void approve_fromNotStarted_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.NOT_STARTED).approve());
    }
    @Test void approve_fromApproved_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.APPROVED).approve());
    }
    @Test void approve_fromRejected_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.REJECTED).approve());
    }
    @Test void approve_fromRevoked_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.REVOKED).approve());
    }

    // ===== reject() : CHỈ PENDING -> REJECTED =====
    @Test void reject_fromPending_ok() {
        KycCase c = caseIn(KycStatus.PENDING);
        c.reject();
        assertEquals(KycStatus.REJECTED, c.getStatus());
    }
    @Test void reject_fromNotStarted_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.NOT_STARTED).reject());
    }
    @Test void reject_fromApproved_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.APPROVED).reject());
    }
    @Test void reject_fromRejected_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.REJECTED).reject());
    }
    @Test void reject_fromRevoked_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.REVOKED).reject());
    }

    // ===== revoke() : CHỈ APPROVED -> REVOKED =====
    @Test void revoke_fromApproved_ok() {
        KycCase c = caseIn(KycStatus.APPROVED);
        c.revoke();
        assertEquals(KycStatus.REVOKED, c.getStatus());
    }
    @Test void revoke_fromNotStarted_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.NOT_STARTED).revoke());
    }
    @Test void revoke_fromPending_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.PENDING).revoke());
    }
    @Test void revoke_fromRejected_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.REJECTED).revoke());
    }
    @Test void revoke_fromRevoked_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.REVOKED).revoke());
    }

    @Test void startNew_isNotStarted() {
        KycCase c = KycCase.startNew("user-9");
        assertEquals(KycStatus.NOT_STARTED, c.getStatus());
        assertNull(c.getCurrentSubmissionId());
    }
}
