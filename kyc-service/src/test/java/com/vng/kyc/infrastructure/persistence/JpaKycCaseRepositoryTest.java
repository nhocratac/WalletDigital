package com.vng.kyc.infrastructure.persistence;

import com.vng.kyc.domain.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import(JpaKycCaseRepository.class)
class JpaKycCaseRepositoryTest {

    @Autowired KycCaseRepository repository;
    @Autowired TestEntityManager em;
    @Autowired SpringDataKycDecisionJpa decisionJpa;

    @Test
    void caseRoundTrip_throughRealDb() {
        KycCase c = KycCase.startNew("user-1");
        c.submit("sub-1");
        repository.save(c);
        em.flush(); em.clear(); // ép đọc lại từ DB, không từ L1 cache

        KycCase found = repository.findByUserId("user-1").orElseThrow();
        assertEquals(KycStatus.PENDING, found.getStatus());
        assertEquals("sub-1", found.getCurrentSubmissionId());
    }

    @Test
    void submissionAndDecision_roundTrip() {
        repository.saveSubmission(new KycSubmission("sub-1", "user-1", List.of("ref-a", "ref-b"), Instant.now()));
        repository.saveDecision(new KycDecision("dec-1", "sub-1", KycDecision.Type.APPROVE, "v", "ok", Instant.now()));
        em.flush(); em.clear();

        assertTrue(repository.findSubmission("sub-1").isPresent());
        assertEquals(List.of("ref-a", "ref-b"), repository.findSubmission("sub-1").orElseThrow().documentRefs());
        assertTrue(repository.decisionExistsForSubmission("sub-1"));
        assertFalse(repository.decisionExistsForSubmission("sub-2"));
    }

    @Test
    void duplicateDecisionForSameSubmission_violatesDbConstraint() {
        repository.saveDecision(new KycDecision("dec-1", "sub-1", KycDecision.Type.APPROVE, "v", "ok", Instant.now()));
        em.flush();

        // UNIQUE(submission_id, type) là chốt chặn idempotency Ở TẦNG DB
        assertThrows(DataIntegrityViolationException.class, () -> {
            repository.saveDecision(new KycDecision("dec-2", "sub-1", KycDecision.Type.APPROVE, "v", "x", Instant.now()));
            decisionJpa.flush(); // flush qua proxy Spring Data để có exception translation
        });
    }

    @Test
    void revokeDecisionForSameSubmission_isAllowedByCompositeConstraint() {
        repository.saveDecision(new KycDecision("dec-1", "sub-1", KycDecision.Type.APPROVE, "v", "ok", Instant.now()));
        em.flush();

        // Hợp đồng mới: REVOKE cho cùng submission là hợp lệ (khác type)
        repository.saveDecision(new KycDecision("dec-2", "sub-1", KycDecision.Type.REVOKE, "c", "fraud", Instant.now()));
        decisionJpa.flush();

        assertEquals(2, decisionJpa.findAll().stream()
                .filter(d -> d.getSubmissionId().equals("sub-1")).count());
    }
}
