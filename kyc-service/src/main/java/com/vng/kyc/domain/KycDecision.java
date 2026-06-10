package com.vng.kyc.domain;

import java.time.Instant;

/** Một quyết định — BẤT BIẾN (audit ledger). */
public record KycDecision(String id, String submissionId, Type type,
                          String decidedBy, String reason, Instant decidedAt) {
    public enum Type { APPROVE, REJECT, REVOKE }
}
