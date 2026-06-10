package com.vng.kyc.domain;

import java.time.Instant;
import java.util.List;

/** Một lần nộp hồ sơ — BẤT BIẾN (audit ledger). Chỉ giữ refs, KHÔNG file thật (PII). */
public record KycSubmission(String id, String userId, List<String> documentRefs, Instant submittedAt) {
}
