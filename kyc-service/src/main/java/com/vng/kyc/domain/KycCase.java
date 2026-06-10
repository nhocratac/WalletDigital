package com.vng.kyc.domain;

/**
 * Trạng thái KYC HIỆN TẠI của một user. Luật chuyển trạng thái ÉP TẠI ĐÂY
 * (make illegal states unrepresentable) — không rải if ở controller/service.
 * Thuần Java — KHÔNG import Spring/JPA.
 */
public class KycCase {

    private final String userId;
    private KycStatus status;
    private String currentSubmissionId;
    private final Long version; // optimistic lock, do persistence quản lý

    public KycCase(String userId, KycStatus status, String currentSubmissionId, Long version) {
        this.userId = userId;
        this.status = status;
        this.currentSubmissionId = currentSubmissionId;
        this.version = version;
    }

    public static KycCase startNew(String userId) {
        return new KycCase(userId, KycStatus.NOT_STARTED, null, null);
    }

    /** Nộp hồ sơ: chỉ từ NOT_STARTED / REJECTED / REVOKED. */
    public void submit(String submissionId) {
        if (status != KycStatus.NOT_STARTED && status != KycStatus.REJECTED
                && status != KycStatus.REVOKED) {
            throw new InvalidKycTransitionException(status, "submit");
        }
        this.status = KycStatus.PENDING;
        this.currentSubmissionId = submissionId;
    }

    /** Duyệt: CHỈ từ PENDING. */
    public void approve() {
        if (status != KycStatus.PENDING) {
            throw new InvalidKycTransitionException(status, "approve");
        }
        this.status = KycStatus.APPROVED;
    }

    /** Từ chối: CHỈ từ PENDING. */
    public void reject() {
        if (status != KycStatus.PENDING) {
            throw new InvalidKycTransitionException(status, "reject");
        }
        this.status = KycStatus.REJECTED;
    }

    /** Thu hồi: CHỈ từ APPROVED. */
    public void revoke() {
        if (status != KycStatus.APPROVED) {
            throw new InvalidKycTransitionException(status, "revoke");
        }
        this.status = KycStatus.REVOKED;
    }

    public String getUserId() { return userId; }
    public KycStatus getStatus() { return status; }
    public String getCurrentSubmissionId() { return currentSubmissionId; }
    public Long getVersion() { return version; }
}
