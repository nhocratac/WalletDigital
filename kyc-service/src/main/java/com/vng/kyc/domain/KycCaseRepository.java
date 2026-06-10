package com.vng.kyc.domain;

import java.util.Optional;

/** PORT — gom cả 3 bảng của aggregate KYC (case là aggregate root). */
public interface KycCaseRepository {
    KycCase save(KycCase kycCase);
    Optional<KycCase> findByUserId(String userId);
    KycSubmission saveSubmission(KycSubmission submission);
    Optional<KycSubmission> findSubmission(String submissionId);
    KycDecision saveDecision(KycDecision decision);
    boolean decisionExistsForSubmission(String submissionId);
}
