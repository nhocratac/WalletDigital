package com.vng.kyc.application;

import com.vng.kyc.domain.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class KycServiceTest {

    static class FakeRepo implements KycCaseRepository {
        Map<String, KycCase> cases = new HashMap<>();
        Map<String, KycSubmission> subs = new HashMap<>();
        Map<String, KycDecision> decisions = new HashMap<>(); // key = decision id
        Set<String> decidedSubmissions = new HashSet<>();

        public KycCase save(KycCase c) { cases.put(c.getUserId(), c); return c; }
        public Optional<KycCase> findByUserId(String u) { return Optional.ofNullable(cases.get(u)); }
        public KycSubmission saveSubmission(KycSubmission s) { subs.put(s.id(), s); return s; }
        public Optional<KycSubmission> findSubmission(String id) { return Optional.ofNullable(subs.get(id)); }
        public KycDecision saveDecision(KycDecision d) {
            decisions.put(d.id(), d); decidedSubmissions.add(d.submissionId()); return d;
        }
        public boolean decisionExistsForSubmission(String id) { return decidedSubmissions.contains(id); }
    }

    static class FakePublisher implements KycEventPublisher {
        record Revoked(String userId, String reason) {}
        List<Revoked> events = new ArrayList<>();
        public void publishKycRevoked(String userId, String reason) {
            events.add(new Revoked(userId, reason));
        }
    }

    private final FakeRepo repo = new FakeRepo();
    private final FakePublisher publisher = new FakePublisher();
    private final KycService service = new KycService(repo, publisher);

    @Test
    void submit_createsImmutableSubmissionAndPendingCase() {
        String subId = service.submit("user-1", List.of("doc-ref-1"));

        assertNotNull(subId);
        assertTrue(repo.findSubmission(subId).isPresent());
        KycCase c = repo.findByUserId("user-1").orElseThrow();
        assertEquals(KycStatus.PENDING, c.getStatus());
        assertEquals(subId, c.getCurrentSubmissionId());
    }

    @Test
    void applyDecision_approve_transitionsAndRecordsDecision() {
        String subId = service.submit("user-1", List.of("d"));

        KycService.DecisionResult r = service.applyDecision(subId, KycDecision.Type.APPROVE, "verifier-x", "ok");

        assertEquals(KycService.DecisionResult.APPLIED, r);
        assertEquals(KycStatus.APPROVED, repo.findByUserId("user-1").orElseThrow().getStatus());
        assertTrue(repo.decisionExistsForSubmission(subId));
    }

    @Test
    void applyDecision_duplicate_isIdempotentNoop() {
        String subId = service.submit("user-1", List.of("d"));
        service.applyDecision(subId, KycDecision.Type.APPROVE, "v", "ok");
        int decisionsBefore = repo.decisions.size();

        KycService.DecisionResult r = service.applyDecision(subId, KycDecision.Type.REJECT, "v", "again");

        assertEquals(KycService.DecisionResult.DUPLICATE_IGNORED, r);
        assertEquals(KycStatus.APPROVED, repo.findByUserId("user-1").orElseThrow().getStatus(), "trạng thái KHÔNG lật");
        assertEquals(decisionsBefore, repo.decisions.size(), "KHÔNG ghi decision thứ hai");
    }

    @Test
    void applyDecision_staleSubmission_isIgnored() {
        String oldSub = service.submit("user-1", List.of("d1"));
        service.applyDecision(oldSub, KycDecision.Type.REJECT, "v", "blurry photo");
        String newSub = service.submit("user-1", List.of("d2")); // resubmit -> PENDING
        int decisionsBefore = repo.decisions.size();

        KycService.DecisionResult r = service.applyDecision(oldSub, KycDecision.Type.APPROVE, "v", "late");

        assertEquals(KycService.DecisionResult.STALE_IGNORED, r);
        assertEquals(KycStatus.PENDING, repo.findByUserId("user-1").orElseThrow().getStatus(), "submission cũ không có hiệu lực");
        assertEquals(newSub, repo.findByUserId("user-1").orElseThrow().getCurrentSubmissionId());
        assertEquals(decisionsBefore, repo.decisions.size(), "không được ghi decision cho submission cũ");
    }

    @Test
    void applyDecision_unknownSubmission_throws() {
        assertThrows(SubmissionNotFoundException.class,
                () -> service.applyDecision("nope", KycDecision.Type.APPROVE, "v", "r"));
    }

    @Test
    void applyDecision_revokeType_isProgrammerErrorGuard() {
        String subId = service.submit("user-1", List.of("d"));
        int decisionsBefore = repo.decisions.size();

        assertThrows(IllegalArgumentException.class,
                () -> service.applyDecision(subId, KycDecision.Type.REVOKE, "v", "r"));

        assertEquals(decisionsBefore, repo.decisions.size(), "không được ghi decision");
        assertEquals(KycStatus.PENDING, repo.findByUserId("user-1").orElseThrow().getStatus(),
                "trạng thái không đổi");
    }

    @Test
    void revoke_fromApproved_publishesEvent() {
        String subId = service.submit("user-1", List.of("d"));
        service.applyDecision(subId, KycDecision.Type.APPROVE, "v", "ok");
        int decisionsBefore = repo.decisions.size();

        service.revoke("user-1", "compliance-officer", "fraud detected");

        assertEquals(KycStatus.REVOKED, repo.findByUserId("user-1").orElseThrow().getStatus());
        assertEquals(List.of(new FakePublisher.Revoked("user-1", "fraud detected")),
                publisher.events, "event kyc.revoked phải được phát kèm đúng reason");
        assertEquals(decisionsBefore + 1, repo.decisions.size(),
                "REVOKE phải được ghi vào audit ledger");
        KycDecision d = repo.decisions.values().stream()
                .filter(x -> x.type() == KycDecision.Type.REVOKE)
                .findFirst().orElseThrow();
        assertEquals(subId, d.submissionId());
        assertEquals("compliance-officer", d.decidedBy());
        assertEquals("fraud detected", d.reason());
    }

    @Test
    void getStatus_unknownUser_returnsNotStarted() {
        assertEquals(KycStatus.NOT_STARTED, service.getStatus("never-seen"));
    }
}
