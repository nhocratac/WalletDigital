package com.vng.kyc.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataKycSubmissionJpa extends JpaRepository<KycSubmissionEntity, String> {}
