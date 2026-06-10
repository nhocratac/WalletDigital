package com.vng.kyc.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "kyc_submission")
public class KycSubmissionEntity {
    @Id
    private String id;
    private String userId;
    @Column(length = 2000)
    private String documentRefs; // CSV — đủ cho học; refs không chứa dấu phẩy
    private Instant submittedAt;

    protected KycSubmissionEntity() {}
    public KycSubmissionEntity(String id, String userId, String documentRefs, Instant submittedAt) {
        this.id = id; this.userId = userId; this.documentRefs = documentRefs; this.submittedAt = submittedAt;
    }
    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getDocumentRefs() { return documentRefs; }
    public Instant getSubmittedAt() { return submittedAt; }
}
