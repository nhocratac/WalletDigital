package com.vng.kyc.application;

import com.vng.kyc.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class KycService {

    /** Kết quả áp một decision — phân biệt APPLIED với 2 loại no-op có chủ đích. */
    public enum DecisionResult { APPLIED, DUPLICATE_IGNORED, STALE_IGNORED }

    private static final Logger log = LoggerFactory.getLogger(KycService.class);

    private final KycCaseRepository repository;
    private final KycEventPublisher eventPublisher;

    public KycService(KycCaseRepository repository, KycEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public String submit(String userId, List<String> documentRefs) {
        KycCase kycCase = repository.findByUserId(userId).orElseGet(() -> KycCase.startNew(userId));
        KycSubmission submission = new KycSubmission(
                UUID.randomUUID().toString(), userId, documentRefs, Instant.now());
        kycCase.submit(submission.id()); // domain ép luật transition
        repository.saveSubmission(submission);
        repository.save(kycCase);
        return submission.id();
    }

    @Transactional
    public DecisionResult applyDecision(String submissionId, KycDecision.Type type,
                                        String decidedBy, String reason) {
        KycSubmission submission = repository.findSubmission(submissionId)
                .orElseThrow(() -> new SubmissionNotFoundException(submissionId));
        KycCase kycCase = repository.findByUserId(submission.userId()).orElseThrow(() -> {
            // Submission tồn tại nhưng case thì không — bất thường về toàn vẹn dữ liệu.
            log.error("Data integrity anomaly: submission {} exists but no KYC case for user {}",
                    submissionId, submission.userId());
            return new KycCaseNotFoundException(submission.userId());
        });
        if (!submissionId.equals(kycCase.getCurrentSubmissionId())) {
            return DecisionResult.STALE_IGNORED;       // user đã nộp lại — quyết định cũ vô hiệu
        }
        if (repository.decisionExistsForSubmission(submissionId)) {
            return DecisionResult.DUPLICATE_IGNORED;   // verifier retry — idempotent
        }
        switch (type) {
            case APPROVE -> kycCase.approve();
            case REJECT -> kycCase.reject();
            case REVOKE -> throw new IllegalArgumentException(
                    "REVOKE cannot be applied as a submission decision; use revoke(userId, ...)");
        }
        repository.saveDecision(new KycDecision(UUID.randomUUID().toString(),
                submissionId, type, decidedBy, reason, Instant.now()));
        repository.save(kycCase);
        return DecisionResult.APPLIED;
    }

    @Transactional
    public void revoke(String userId, String decidedBy, String reason) {
        KycCase kycCase = repository.findByUserId(userId)
                .orElseThrow(() -> new KycCaseNotFoundException(userId));
        kycCase.revoke(); // chỉ APPROVED -> REVOKED
        // UNIQUE(submission_id) đã giữ chỗ cho decision APPROVE của submission này;
        // bản ghi REVOKE tham chiếu "revoke:<submissionId>" — vẫn truy vết được, vẫn duy nhất.
        repository.saveDecision(new KycDecision(UUID.randomUUID().toString(),
                "revoke:" + kycCase.getCurrentSubmissionId(), KycDecision.Type.REVOKE, decidedBy, reason, Instant.now()));
        repository.save(kycCase);
        eventPublisher.publishKycRevoked(userId, reason);
    }

    @Transactional(readOnly = true)
    public KycStatus getStatus(String userId) {
        return repository.findByUserId(userId).map(KycCase::getStatus)
                .orElse(KycStatus.NOT_STARTED); // trạng thái nghiệp vụ hợp lệ, không phải lỗi
    }
}
