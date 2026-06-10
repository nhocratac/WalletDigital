package com.vng.kyc.infrastructure.persistence;

import com.vng.kyc.domain.*;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** ADAPTER: cài port domain bằng JPA, map entity ↔ domain. */
@Repository
public class JpaKycCaseRepository implements KycCaseRepository {

    private final SpringDataKycCaseJpa caseJpa;
    private final SpringDataKycSubmissionJpa submissionJpa;
    private final SpringDataKycDecisionJpa decisionJpa;

    public JpaKycCaseRepository(SpringDataKycCaseJpa caseJpa,
                                SpringDataKycSubmissionJpa submissionJpa,
                                SpringDataKycDecisionJpa decisionJpa) {
        this.caseJpa = caseJpa;
        this.submissionJpa = submissionJpa;
        this.decisionJpa = decisionJpa;
    }

    @Override
    public KycCase save(KycCase c) {
        KycCaseEntity saved = caseJpa.save(new KycCaseEntity(
                c.getUserId(), c.getStatus(), c.getCurrentSubmissionId(), c.getVersion()));
        return toDomain(saved);
    }

    @Override
    public Optional<KycCase> findByUserId(String userId) {
        return caseJpa.findById(userId).map(this::toDomain);
    }

    @Override
    public KycSubmission saveSubmission(KycSubmission s) {
        submissionJpa.save(new KycSubmissionEntity(
                s.id(), s.userId(), String.join(",", s.documentRefs()), s.submittedAt()));
        return s;
    }

    @Override
    public Optional<KycSubmission> findSubmission(String submissionId) {
        return submissionJpa.findById(submissionId).map(e -> new KycSubmission(
                e.getId(), e.getUserId(),
                e.getDocumentRefs().isEmpty() ? List.of() : Arrays.asList(e.getDocumentRefs().split(",")),
                e.getSubmittedAt()));
    }

    @Override
    public KycDecision saveDecision(KycDecision d) {
        // saveAndFlush: ép vi phạm UNIQUE nổi lên NGAY TRONG transaction (đã được Spring
        // translate thành DataIntegrityViolationException) thay vì lúc commit — nơi nó có thể
        // bị bọc trong TransactionSystemException và lọt qua mọi catch ở tầng trên.
        decisionJpa.saveAndFlush(new KycDecisionEntity(
                d.id(), d.submissionId(), d.type(), d.decidedBy(), d.reason(), d.decidedAt()));
        return d;
    }

    @Override
    public boolean decisionExistsForSubmission(String submissionId) {
        return decisionJpa.existsBySubmissionId(submissionId);
    }

    private KycCase toDomain(KycCaseEntity e) {
        return new KycCase(e.getUserId(), e.getStatus(), e.getCurrentSubmissionId(), e.getVersion());
    }
}
