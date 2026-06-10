package com.vng.kyc.application;

import com.vng.kyc.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class KycService {

    /** Kết quả áp một decision — phân biệt APPLIED với 2 loại no-op có chủ đích. */
    public enum DecisionResult { APPLIED, DUPLICATE_IGNORED, STALE_IGNORED }

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
        KycCase kycCase = repository.findByUserId(submission.userId()).orElseThrow();
        if (!submissionId.equals(kycCase.getCurrentSubmissionId())) {
            return DecisionResult.STALE_IGNORED;       // user đã nộp lại — quyết định cũ vô hiệu
        }
        if (repository.decisionExistsForSubmission(submissionId)) {
            return DecisionResult.DUPLICATE_IGNORED;   // verifier retry — idempotent
        }
        switch (type) {
            case APPROVE -> kycCase.approve();
            case REJECT -> kycCase.reject();
            case REVOKE -> throw new InvalidKycTransitionException(kycCase.getStatus(), "revoke-via-webhook");
        }
        repository.saveDecision(new KycDecision(UUID.randomUUID().toString(),
                submissionId, type, decidedBy, reason, Instant.now()));
        repository.save(kycCase);
        return DecisionResult.APPLIED;
    }

    @Transactional
    public void revoke(String userId, String decidedBy, String reason) {
        KycCase kycCase = repository.findByUserId(userId).orElseThrow();
        kycCase.revoke(); // chỉ APPROVED -> REVOKED
        repository.saveDecision(new KycDecision(UUID.randomUUID().toString(),
                kycCase.getCurrentSubmissionId(), KycDecision.Type.REVOKE, decidedBy, reason, Instant.now()));
        repository.save(kycCase);
        eventPublisher.publishKycRevoked(userId, reason);
    }

    @Transactional(readOnly = true)
    public KycStatus getStatus(String userId) {
        return repository.findByUserId(userId).map(KycCase::getStatus)
                .orElse(KycStatus.NOT_STARTED); // trạng thái nghiệp vụ hợp lệ, không phải lỗi
    }
}
