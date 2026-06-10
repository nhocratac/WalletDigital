package com.vng.kyc.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "kyc_decision",
       // Idempotency tầng DB theo (submission, type); loại trừ chéo APPROVE vs REJECT
       // do state machine của KycCase + @Version optimistic lock trên kyc_case
       // trong cùng transaction đảm nhiệm.
       uniqueConstraints = @UniqueConstraint(columnNames = {"submissionId", "type"}))
public class KycDecisionEntity {
    @Id
    private String id;
    private String submissionId;
    @Enumerated(EnumType.STRING)
    private com.vng.kyc.domain.KycDecision.Type type;
    private String decidedBy;
    private String reason;
    private Instant decidedAt;

    protected KycDecisionEntity() {}
    public KycDecisionEntity(String id, String submissionId, com.vng.kyc.domain.KycDecision.Type type,
                             String decidedBy, String reason, Instant decidedAt) {
        this.id = id; this.submissionId = submissionId; this.type = type;
        this.decidedBy = decidedBy; this.reason = reason; this.decidedAt = decidedAt;
    }
    public String getId() { return id; }
    public String getSubmissionId() { return submissionId; }
    public com.vng.kyc.domain.KycDecision.Type getType() { return type; }
    public String getDecidedBy() { return decidedBy; }
    public String getReason() { return reason; }
    public Instant getDecidedAt() { return decidedAt; }
}
