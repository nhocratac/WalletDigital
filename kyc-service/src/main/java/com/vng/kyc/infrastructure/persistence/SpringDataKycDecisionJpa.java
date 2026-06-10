package com.vng.kyc.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataKycDecisionJpa extends JpaRepository<KycDecisionEntity, String> {
    boolean existsBySubmissionId(String submissionId);
}
