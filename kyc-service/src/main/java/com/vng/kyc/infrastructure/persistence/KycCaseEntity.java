package com.vng.kyc.infrastructure.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "kyc_case")
public class KycCaseEntity {
    @Id
    private String userId;
    @Enumerated(EnumType.STRING)
    private com.vng.kyc.domain.KycStatus status;
    private String currentSubmissionId;
    @Version
    private Long version; // optimistic lock

    protected KycCaseEntity() {}
    public KycCaseEntity(String userId, com.vng.kyc.domain.KycStatus status,
                         String currentSubmissionId, Long version) {
        this.userId = userId; this.status = status;
        this.currentSubmissionId = currentSubmissionId; this.version = version;
    }
    public String getUserId() { return userId; }
    public com.vng.kyc.domain.KycStatus getStatus() { return status; }
    public String getCurrentSubmissionId() { return currentSubmissionId; }
    public Long getVersion() { return version; }
}
