# KYC Service Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Xây `kyc-service` (SP2): state machine KYC 5 trạng thái với audit ledger bất biến, webhook idempotent + chống lạc hậu, bảo mật đa biên (HMAC nội bộ + HMAC verifier riêng + role check), event publisher port (adapter log).

**Architecture:** Clean Architecture với domain DÀY (luật transition ép trong `KycCase`). Ledger: `KycSubmission` + `KycDecision` bất biến, `UNIQUE(submission_id)` chốt idempotency ở DB. 4 biên auth khác nhau trên cùng service.

**Tech Stack:** Java 25, Spring Boot 3.4.4, Spring Data JPA, H2, JUnit 5, Maven.

**Bố cục:** Project Maven ĐỘC LẬP tại `kyc-service/` (như `api-gateway/`). Port **8082**. Spec: `docs/superpowers/specs/2026-06-10-kyc-service-design.md`.

**Phạm vi:** KHÔNG Kafka (adapter log), KHÔNG tích hợp wallet (SP3), KHÔNG tiers/file storage.

---

## Cấu trúc file (sau khi xong)

```
kyc-service/
├── pom.xml
└── src
    ├── main/java/com/vng/kyc/
    │   ├── KycApplication.java
    │   ├── domain/
    │   │   ├── KycStatus.java · KycCase.java · KycSubmission.java · KycDecision.java
    │   │   ├── KycCaseRepository.java · KycEventPublisher.java   (PORTs)
    │   │   ├── InvalidKycTransitionException.java · SubmissionNotFoundException.java
    │   ├── application/KycService.java
    │   └── infrastructure/
    │       ├── persistence/ (KycCaseEntity, KycSubmissionEntity, KycDecisionEntity,
    │       │                 SpringData*Jpa, JpaKycCaseRepository)
    │       ├── security/ (HmacVerifier, InternalAuthFilter, WebhookAuthFilter)
    │       ├── events/LoggingKycEventPublisher.java
    │       ├── config/KycProperties.java
    │       └── web/ (KycController, WebhookController, dto/, GlobalExceptionHandler)
    ├── main/resources/application.yml
    └── test/java/com/vng/kyc/ (mirror theo package)
```

---

## Task 1: Scaffold project (port 8082) + smoke test

**Files:**
- Create: `kyc-service/pom.xml`, `kyc-service/src/main/java/com/vng/kyc/KycApplication.java`, `kyc-service/src/main/resources/application.yml`
- Test: `kyc-service/src/test/java/com/vng/kyc/KycApplicationTest.java`

- [ ] **Step 1: `pom.xml`** — y cấu trúc `api-gateway/pom.xml` nhưng: `groupId=com.vng.kyc`, `artifactId=kyc-service`, `<java.version>25</java.version>`, dependencies: `spring-boot-starter-web`, `spring-boot-starter-data-jpa`, `spring-boot-starter-validation`, `h2` (runtime), `spring-boot-starter-test` (test). KHÔNG jjwt/mockwebserver.

- [ ] **Step 2: `KycApplication.java`**

```java
package com.vng.kyc;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class KycApplication {
    public static void main(String[] args) {
        SpringApplication.run(KycApplication.class, args);
    }
}
```

- [ ] **Step 3: `application.yml`**

```yaml
server:
  port: 8082
spring:
  application:
    name: kyc-service
  datasource:
    url: jdbc:h2:mem:kycdb
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update   # nợ kỹ thuật đã ghi nhận — production dùng Flyway

kyc:
  internal-hmac-secret: ${KYC_INTERNAL_HMAC_SECRET:local-dev-secret}
  verifier-hmac-secret: ${KYC_VERIFIER_HMAC_SECRET:verifier-dev-secret}
  allowed-services: api-gateway,wallet-service
  revoke-role: compliance
```

- [ ] **Step 4: Smoke test**

```java
package com.vng.kyc;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class KycApplicationTest {
    @Test
    void contextLoads() {
    }
}
```

- [ ] **Step 5:** Run `cd kyc-service && mvn -q test -Dtest=KycApplicationTest` → PASS.
- [ ] **Step 6:** `git add kyc-service && git commit -m "feat(kyc): scaffold service on port 8082"`

---

## Task 2: Domain — `KycStatus` + `KycCase` với ma trận transition

**Files:**
- Create: `kyc-service/src/main/java/com/vng/kyc/domain/KycStatus.java`, `.../domain/KycCase.java`, `.../domain/InvalidKycTransitionException.java`
- Test: `kyc-service/src/test/java/com/vng/kyc/domain/KycCaseTest.java`

- [ ] **Step 1: Viết test ma trận thất bại (20 ô = 5 trạng thái × 4 hành động)**

```java
package com.vng.kyc.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KycCaseTest {

    private KycCase caseIn(KycStatus status) {
        // rehydrate constructor: userId, status, currentSubmissionId, version
        return new KycCase("user-1", status, status == KycStatus.NOT_STARTED ? null : "sub-old", 0L);
    }

    // ===== submit() : NOT_STARTED/REJECTED/REVOKED -> PENDING =====
    @Test void submit_fromNotStarted_ok() {
        KycCase c = caseIn(KycStatus.NOT_STARTED);
        c.submit("sub-1");
        assertEquals(KycStatus.PENDING, c.getStatus());
        assertEquals("sub-1", c.getCurrentSubmissionId());
    }
    @Test void submit_fromRejected_ok() {
        KycCase c = caseIn(KycStatus.REJECTED);
        c.submit("sub-2");
        assertEquals(KycStatus.PENDING, c.getStatus());
        assertEquals("sub-2", c.getCurrentSubmissionId());
    }
    @Test void submit_fromRevoked_ok() {
        KycCase c = caseIn(KycStatus.REVOKED);
        c.submit("sub-2");
        assertEquals(KycStatus.PENDING, c.getStatus());
    }
    @Test void submit_fromPending_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.PENDING).submit("s"));
    }
    @Test void submit_fromApproved_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.APPROVED).submit("s"));
    }

    // ===== approve() : CHỈ PENDING -> APPROVED =====
    @Test void approve_fromPending_ok() {
        KycCase c = caseIn(KycStatus.PENDING);
        c.approve();
        assertEquals(KycStatus.APPROVED, c.getStatus());
    }
    @Test void approve_fromNotStarted_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.NOT_STARTED).approve());
    }
    @Test void approve_fromApproved_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.APPROVED).approve());
    }
    @Test void approve_fromRejected_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.REJECTED).approve());
    }
    @Test void approve_fromRevoked_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.REVOKED).approve());
    }

    // ===== reject() : CHỈ PENDING -> REJECTED =====
    @Test void reject_fromPending_ok() {
        KycCase c = caseIn(KycStatus.PENDING);
        c.reject();
        assertEquals(KycStatus.REJECTED, c.getStatus());
    }
    @Test void reject_fromNotStarted_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.NOT_STARTED).reject());
    }
    @Test void reject_fromApproved_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.APPROVED).reject());
    }
    @Test void reject_fromRejected_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.REJECTED).reject());
    }
    @Test void reject_fromRevoked_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.REVOKED).reject());
    }

    // ===== revoke() : CHỈ APPROVED -> REVOKED =====
    @Test void revoke_fromApproved_ok() {
        KycCase c = caseIn(KycStatus.APPROVED);
        c.revoke();
        assertEquals(KycStatus.REVOKED, c.getStatus());
    }
    @Test void revoke_fromNotStarted_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.NOT_STARTED).revoke());
    }
    @Test void revoke_fromPending_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.PENDING).revoke());
    }
    @Test void revoke_fromRejected_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.REJECTED).revoke());
    }
    @Test void revoke_fromRevoked_throws() {
        assertThrows(InvalidKycTransitionException.class, () -> caseIn(KycStatus.REVOKED).revoke());
    }

    @Test void startNew_isNotStarted() {
        KycCase c = KycCase.startNew("user-9");
        assertEquals(KycStatus.NOT_STARTED, c.getStatus());
        assertNull(c.getCurrentSubmissionId());
    }
}
```

- [ ] **Step 2:** Run `cd kyc-service && mvn -q test -Dtest=KycCaseTest` → FAIL (class chưa tồn tại).

- [ ] **Step 3: Implement domain**

```java
package com.vng.kyc.domain;

public enum KycStatus {
    NOT_STARTED, PENDING, APPROVED, REJECTED, REVOKED
}
```

```java
package com.vng.kyc.domain;

public class InvalidKycTransitionException extends RuntimeException {
    public InvalidKycTransitionException(KycStatus from, String action) {
        super("Cannot " + action + " from status " + from);
    }
}
```

```java
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
```

- [ ] **Step 4:** Run `mvn -q test -Dtest=KycCaseTest` → PASS (21 tests).
- [ ] **Step 5:** `git add kyc-service && git commit -m "feat(kyc): domain state machine with full transition matrix"`

---

## Task 3: Domain — `KycSubmission`, `KycDecision`, ports, exceptions

**Files:**
- Create: `.../domain/KycSubmission.java`, `.../domain/KycDecision.java`, `.../domain/KycCaseRepository.java`, `.../domain/KycEventPublisher.java`, `.../domain/SubmissionNotFoundException.java`

- [ ] **Step 1: Hai record bất biến**

```java
package com.vng.kyc.domain;

import java.time.Instant;
import java.util.List;

/** Một lần nộp hồ sơ — BẤT BIẾN (audit ledger). Chỉ giữ refs, KHÔNG file thật (PII). */
public record KycSubmission(String id, String userId, List<String> documentRefs, Instant submittedAt) {
}
```

```java
package com.vng.kyc.domain;

import java.time.Instant;

/** Một quyết định — BẤT BIẾN (audit ledger). */
public record KycDecision(String id, String submissionId, Type type,
                          String decidedBy, String reason, Instant decidedAt) {
    public enum Type { APPROVE, REJECT, REVOKE }
}
```

- [ ] **Step 2: Ports + exception**

```java
package com.vng.kyc.domain;

import java.util.Optional;

/** PORT — gom cả 3 bảng của aggregate KYC (case là aggregate root). */
public interface KycCaseRepository {
    KycCase save(KycCase kycCase);
    Optional<KycCase> findByUserId(String userId);
    KycSubmission saveSubmission(KycSubmission submission);
    Optional<KycSubmission> findSubmission(String submissionId);
    KycDecision saveDecision(KycDecision decision);
    boolean decisionExistsForSubmission(String submissionId);
}
```

```java
package com.vng.kyc.domain;

/** PORT — phát event nghiệp vụ. SP2: adapter log; SP3: Kafka. */
public interface KycEventPublisher {
    void publishKycRevoked(String userId, String reason);
}
```

```java
package com.vng.kyc.domain;

public class SubmissionNotFoundException extends RuntimeException {
    public SubmissionNotFoundException(String submissionId) {
        super("Submission not found: " + submissionId);
    }
}
```

- [ ] **Step 3:** Run `cd kyc-service && mvn -q compile` → BUILD SUCCESS.
- [ ] **Step 4:** `git add kyc-service && git commit -m "feat(kyc): immutable ledger records + ports"`

---

## Task 4: Application — `KycService` (submit / applyDecision idempotent+stale / revoke / getStatus)

**Files:**
- Create: `kyc-service/src/main/java/com/vng/kyc/application/KycService.java`
- Test: `kyc-service/src/test/java/com/vng/kyc/application/KycServiceTest.java`

- [ ] **Step 1: Test thất bại (fake repo + fake publisher)**

```java
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
        List<String> revokedUsers = new ArrayList<>();
        public void publishKycRevoked(String userId, String reason) { revokedUsers.add(userId); }
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

        KycService.DecisionResult r = service.applyDecision(oldSub, KycDecision.Type.APPROVE, "v", "late");

        assertEquals(KycService.DecisionResult.STALE_IGNORED, r);
        assertEquals(KycStatus.PENDING, repo.findByUserId("user-1").orElseThrow().getStatus(), "submission cũ không có hiệu lực");
        assertEquals(newSub, repo.findByUserId("user-1").orElseThrow().getCurrentSubmissionId());
    }

    @Test
    void applyDecision_unknownSubmission_throws() {
        assertThrows(SubmissionNotFoundException.class,
                () -> service.applyDecision("nope", KycDecision.Type.APPROVE, "v", "r"));
    }

    @Test
    void revoke_fromApproved_publishesEvent() {
        String subId = service.submit("user-1", List.of("d"));
        service.applyDecision(subId, KycDecision.Type.APPROVE, "v", "ok");

        service.revoke("user-1", "compliance-officer", "fraud detected");

        assertEquals(KycStatus.REVOKED, repo.findByUserId("user-1").orElseThrow().getStatus());
        assertEquals(List.of("user-1"), publisher.revokedUsers, "event kyc.revoked phải được phát");
    }

    @Test
    void getStatus_unknownUser_returnsNotStarted() {
        assertEquals(KycStatus.NOT_STARTED, service.getStatus("never-seen"));
    }
}
```

- [ ] **Step 2:** Run `mvn -q test -Dtest=KycServiceTest` → FAIL.

- [ ] **Step 3: Implement `KycService`**

```java
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
        if (repository.decisionExistsForSubmission(submissionId)) {
            return DecisionResult.DUPLICATE_IGNORED;   // verifier retry — idempotent
        }
        KycCase kycCase = repository.findByUserId(submission.userId()).orElseThrow();
        if (!submissionId.equals(kycCase.getCurrentSubmissionId())) {
            return DecisionResult.STALE_IGNORED;       // user đã nộp lại — quyết định cũ vô hiệu
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
```

- [ ] **Step 4:** Run `mvn -q test -Dtest=KycServiceTest` → PASS (7 tests).
- [ ] **Step 5:** `git add kyc-service && git commit -m "feat(kyc): KycService with idempotent + stale-guarded decisions"`

---

## Task 5: Persistence — entities + adapter + UNIQUE(submission_id)

**Files:**
- Create: `.../infrastructure/persistence/KycCaseEntity.java`, `KycSubmissionEntity.java`, `KycDecisionEntity.java`, `SpringDataKycCaseJpa.java`, `SpringDataKycSubmissionJpa.java`, `SpringDataKycDecisionJpa.java`, `JpaKycCaseRepository.java`
- Test: `kyc-service/src/test/java/com/vng/kyc/infrastructure/persistence/JpaKycCaseRepositoryTest.java`

- [ ] **Step 1: Test thất bại (@DataJpaTest, flush+clear như bài học wallet)**

```java
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

        // UNIQUE(submission_id) là chốt chặn idempotency Ở TẦNG DB
        assertThrows(DataIntegrityViolationException.class, () -> {
            repository.saveDecision(new KycDecision("dec-2", "sub-1", KycDecision.Type.REJECT, "v", "x", Instant.now()));
            em.flush();
        });
    }
}
```

- [ ] **Step 2:** Run `mvn -q test -Dtest=JpaKycCaseRepositoryTest` → FAIL.

- [ ] **Step 3: Entities** (3 file — pattern y `WalletEntity`: protected no-arg ctor, getters)

```java
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
```

```java
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
```

```java
package com.vng.kyc.infrastructure.persistence;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "kyc_decision",
       uniqueConstraints = @UniqueConstraint(columnNames = "submissionId")) // idempotency tầng DB
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
```

- [ ] **Step 4: Spring Data interfaces + adapter**

```java
package com.vng.kyc.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataKycCaseJpa extends JpaRepository<KycCaseEntity, String> {}
```

```java
package com.vng.kyc.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataKycSubmissionJpa extends JpaRepository<KycSubmissionEntity, String> {}
```

```java
package com.vng.kyc.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataKycDecisionJpa extends JpaRepository<KycDecisionEntity, String> {
    boolean existsBySubmissionId(String submissionId);
}
```

```java
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
        decisionJpa.save(new KycDecisionEntity(
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
```

- [ ] **Step 5:** Run `mvn -q test -Dtest=JpaKycCaseRepositoryTest` → PASS (3 tests).
- [ ] **Step 6:** `git add kyc-service && git commit -m "feat(kyc): JPA persistence with DB-level idempotency constraint"`

---

## Task 6: Security — `HmacVerifier` + 2 filter (nội bộ + webhook) + role check

**Files:**
- Create: `.../infrastructure/config/KycProperties.java`, `.../infrastructure/security/HmacVerifier.java`, `.../infrastructure/security/InternalAuthFilter.java`, `.../infrastructure/security/WebhookAuthFilter.java`
- Test: `kyc-service/src/test/java/com/vng/kyc/infrastructure/security/HmacVerifierTest.java`

> Canonical CHUNG HỢP ĐỒNG với gateway: `serviceId\nmethod\npath\ntimestamp\nsha256hex(body)`. Webhook verifier dùng canonical tương tự nhưng `serviceId` = `"verifier"` và secret RIÊNG (secret segmentation).

- [ ] **Step 1: Test thất bại — khoá hợp đồng canonical (giống `HmacRequestSignerTest` của gateway)**

```java
package com.vng.kyc.infrastructure.security;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HmacVerifierTest {

    private final HmacVerifier verifier = new HmacVerifier();

    @Test
    void canonical_matchesGatewayContract() {
        String emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String expected = String.join("\n",
                "api-gateway", "POST", "/kyc/submissions", "1749470000", emptySha);

        assertEquals(expected, verifier.buildCanonical(
                "api-gateway", "POST", "/kyc/submissions", "1749470000", new byte[0]));
    }

    @Test
    void verify_acceptsCorrectSignature_rejectsWrong() throws Exception {
        String secret = "s3cret";
        String canonical = verifier.buildCanonical("api-gateway", "GET", "/kyc/cases/u/status", "1749470000", new byte[0]);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))
            hex.append(String.format("%02x", b));

        assertTrue(verifier.verify(secret, canonical, hex.toString()));
        assertFalse(verifier.verify(secret, canonical, "deadbeef"));
        assertFalse(verifier.verify("wrong-secret", canonical, hex.toString()));
    }

    @Test
    void timestampWithinTolerance_check() {
        long now = 1749470000L;
        assertTrue(verifier.isTimestampFresh(now, now - 200, 300));
        assertFalse(verifier.isTimestampFresh(now, now - 400, 300)); // quá 5 phút -> replay
    }
}
```

- [ ] **Step 2:** Run `mvn -q test -Dtest=HmacVerifierTest` → FAIL.

- [ ] **Step 3: `KycProperties` + `HmacVerifier`**

```java
package com.vng.kyc.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "kyc")
public class KycProperties {
    private String internalHmacSecret;
    private String verifierHmacSecret;   // secret RIÊNG cho webhook — secret segmentation
    private List<String> allowedServices;
    private String revokeRole = "compliance";

    public String getInternalHmacSecret() { return internalHmacSecret; }
    public void setInternalHmacSecret(String v) { this.internalHmacSecret = v; }
    public String getVerifierHmacSecret() { return verifierHmacSecret; }
    public void setVerifierHmacSecret(String v) { this.verifierHmacSecret = v; }
    public List<String> getAllowedServices() { return allowedServices; }
    public void setAllowedServices(List<String> v) { this.allowedServices = v; }
    public String getRevokeRole() { return revokeRole; }
    public void setRevokeRole(String v) { this.revokeRole = v; }
}
```

```java
package com.vng.kyc.infrastructure.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Verify HMAC theo HỢP ĐỒNG chung: serviceId\nmethod\npath\ntimestamp\nsha256hex(body). */
@Component
public class HmacVerifier {

    public String buildCanonical(String serviceId, String method, String path,
                                 String timestamp, byte[] body) {
        return String.join("\n", serviceId, method, path, timestamp, sha256Hex(body));
    }

    /** So sánh CONSTANT-TIME — tránh timing attack. */
    public boolean verify(String secret, String canonical, String signatureHex) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            byte[] provided = hexToBytes(signatureHex);
            return MessageDigest.isEqual(expected, provided);
        } catch (Exception e) {
            return false;
        }
    }

    public boolean isTimestampFresh(long nowEpochSeconds, long timestamp, long toleranceSeconds) {
        return Math.abs(nowEpochSeconds - timestamp) <= toleranceSeconds;
    }

    private String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest(body == null ? new byte[0] : body)) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) return new byte[0];
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
```

- [ ] **Step 4: Hai filter** (cần đọc body để hash → dùng `ContentCachingRequestWrapper` không đủ cho filter chain; đơn giản + đúng cho học: wrapper tự đọc body bytes)

```java
package com.vng.kyc.infrastructure.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/** Đọc body một lần, cho phép downstream đọc lại (body cần cho cả HMAC hash lẫn @RequestBody). */
public class CachedBodyRequest extends HttpServletRequestWrapper {
    private final byte[] body;

    public CachedBodyRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    public byte[] getBody() { return body; }

    @Override
    public ServletInputStream getInputStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            public int read() { return bais.read(); }
            public boolean isFinished() { return bais.available() == 0; }
            public boolean isReady() { return true; }
            public void setReadListener(ReadListener l) {}
        };
    }
}
```

(Tạo thêm file `CachedBodyRequest.java` trong cùng package — đã nằm trong code trên.)

```java
package com.vng.kyc.infrastructure.security;

import com.vng.kyc.infrastructure.config.KycProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Biên NỘI BỘ: mọi path TRỪ /kyc/webhooks/**. Verify HMAC nội bộ + allowlist.
 * Riêng /kyc/cases/{u}/revoke yêu cầu thêm role compliance trong X-Roles (AuthZ).
 */
@Component
@Order(1)
public class InternalAuthFilter extends OncePerRequestFilter {

    private final HmacVerifier hmac;
    private final KycProperties props;

    public InternalAuthFilter(HmacVerifier hmac, KycProperties props) {
        this.hmac = hmac;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getRequestURI().startsWith("/kyc/webhooks/"); // biên webhook có filter riêng
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CachedBodyRequest cached = new CachedBodyRequest(request);
        String serviceId = cached.getHeader("X-Service-Id");
        String timestamp = cached.getHeader("X-Timestamp");
        String signature = cached.getHeader("X-Signature");

        if (serviceId == null || !props.getAllowedServices().contains(serviceId)) {
            write(response, 403, "Service not allowed"); return;
        }
        if (timestamp == null || signature == null
                || !hmac.isTimestampFresh(Instant.now().getEpochSecond(), parseLong(timestamp), 300)) {
            write(response, 401, "Missing or stale signature"); return;
        }
        String canonical = hmac.buildCanonical(serviceId, cached.getMethod(),
                cached.getRequestURI(), timestamp, cached.getBody());
        if (!hmac.verify(props.getInternalHmacSecret(), canonical, signature)) {
            write(response, 401, "Invalid signature"); return;
        }
        // AuthZ: revoke cần role compliance (gateway bóc roles từ JWT, gắn X-Roles)
        if (cached.getRequestURI().endsWith("/revoke")) {
            String roles = cached.getHeader("X-Roles");
            if (roles == null || !java.util.Arrays.asList(roles.split(",")).contains(props.getRevokeRole())) {
                write(response, 403, "Missing role: " + props.getRevokeRole()); return;
            }
        }
        chain.doFilter(cached, response);
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private void write(HttpServletResponse resp, int status, String msg) throws IOException {
        resp.setStatus(status);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
```

```java
package com.vng.kyc.infrastructure.security;

import com.vng.kyc.infrastructure.config.KycProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;

/**
 * Biên WEBHOOK (verifier NGOÀI hệ): chỉ /kyc/webhooks/**.
 * Secret RIÊNG (segmentation) — lộ secret verifier không lan vào nội bộ.
 * Canonical dùng serviceId cố định "verifier".
 */
@Component
@Order(1)
public class WebhookAuthFilter extends OncePerRequestFilter {

    private final HmacVerifier hmac;
    private final KycProperties props;

    public WebhookAuthFilter(HmacVerifier hmac, KycProperties props) {
        this.hmac = hmac;
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/kyc/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        CachedBodyRequest cached = new CachedBodyRequest(request);
        String timestamp = cached.getHeader("X-Timestamp");
        String signature = cached.getHeader("X-Signature");
        if (timestamp == null || signature == null
                || !hmac.isTimestampFresh(Instant.now().getEpochSecond(), parseLong(timestamp), 300)) {
            write(response, 401, "Missing or stale signature"); return;
        }
        String canonical = hmac.buildCanonical("verifier", cached.getMethod(),
                cached.getRequestURI(), timestamp, cached.getBody());
        if (!hmac.verify(props.getVerifierHmacSecret(), canonical, signature)) {
            write(response, 401, "Invalid signature"); return;
        }
        chain.doFilter(cached, response);
    }

    private long parseLong(String s) {
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return 0; }
    }

    private void write(HttpServletResponse resp, int status, String msg) throws IOException {
        resp.setStatus(status);
        resp.setContentType(MediaType.APPLICATION_JSON_VALUE);
        resp.getWriter().write("{\"error\":\"" + msg + "\"}");
    }
}
```

- [ ] **Step 5:** Run `mvn -q test -Dtest=HmacVerifierTest` → PASS (3 tests). `mvn -q compile` → SUCCESS.
- [ ] **Step 6:** `git add kyc-service && git commit -m "feat(kyc): multi-boundary auth (internal HMAC + segmented webhook secret + role check)"`

---

## Task 7: Events — `LoggingKycEventPublisher`

**Files:**
- Create: `.../infrastructure/events/LoggingKycEventPublisher.java`

- [ ] **Step 1: Adapter log (SP3 sẽ thay bằng Kafka)**

```java
package com.vng.kyc.infrastructure.events;

import com.vng.kyc.domain.KycEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** ADAPTER no-op/log cho SP2. SP3: thay bằng KafkaKycEventPublisher (topic kyc.revoked). */
@Component
public class LoggingKycEventPublisher implements KycEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingKycEventPublisher.class);

    @Override
    public void publishKycRevoked(String userId, String reason) {
        log.info("EVENT kyc.revoked userId={} reason={}", userId, reason);
    }
}
```

- [ ] **Step 2:** Run `mvn -q compile` → SUCCESS.
- [ ] **Step 3:** `git add kyc-service && git commit -m "feat(kyc): logging event publisher adapter"`

---

## Task 8: Web — controllers + DTOs + `GlobalExceptionHandler`

**Files:**
- Create: `.../infrastructure/web/dto/SubmitRequest.java`, `SubmitResponse.java`, `DecisionWebhookRequest.java`, `RevokeRequest.java`, `StatusResponse.java`
- Create: `.../infrastructure/web/KycController.java`, `WebhookController.java`, `GlobalExceptionHandler.java`

- [ ] **Step 1: DTOs**

```java
package com.vng.kyc.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record SubmitRequest(@NotBlank String userId, @NotEmpty List<String> documentRefs) {}
```

```java
package com.vng.kyc.infrastructure.web.dto;

public record SubmitResponse(String submissionId) {}
```

```java
package com.vng.kyc.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.vng.kyc.domain.KycDecision;

public record DecisionWebhookRequest(@NotBlank String submissionId,
                                     @NotNull KycDecision.Type decision,
                                     @NotBlank String decidedBy,
                                     String reason) {}
```

```java
package com.vng.kyc.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RevokeRequest(@NotBlank String reason) {}
```

```java
package com.vng.kyc.infrastructure.web.dto;

import com.vng.kyc.domain.KycStatus;

public record StatusResponse(String userId, KycStatus status) {}
```

- [ ] **Step 2: Controllers**

```java
package com.vng.kyc.infrastructure.web;

import com.vng.kyc.application.KycService;
import com.vng.kyc.infrastructure.web.dto.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/kyc")
public class KycController {

    private final KycService kycService;

    public KycController(KycService kycService) {
        this.kycService = kycService;
    }

    @PostMapping("/submissions")
    public ResponseEntity<SubmitResponse> submit(@Valid @RequestBody SubmitRequest req) {
        String submissionId = kycService.submit(req.userId(), req.documentRefs());
        return ResponseEntity.status(HttpStatus.CREATED).body(new SubmitResponse(submissionId));
    }

    @GetMapping("/cases/{userId}/status")
    public StatusResponse status(@PathVariable String userId) {
        return new StatusResponse(userId, kycService.getStatus(userId));
    }

    @PostMapping("/cases/{userId}/revoke")
    public ResponseEntity<Void> revoke(@PathVariable String userId,
                                       @Valid @RequestBody RevokeRequest req,
                                       HttpServletRequest http) {
        String decidedBy = http.getHeader("X-Service-Id"); // ai gọi (đã qua role check ở filter)
        kycService.revoke(userId, decidedBy, req.reason());
        return ResponseEntity.ok().build();
    }
}
```

```java
package com.vng.kyc.infrastructure.web;

import com.vng.kyc.application.KycService;
import com.vng.kyc.infrastructure.web.dto.DecisionWebhookRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/kyc/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final KycService kycService;

    public WebhookController(KycService kycService) {
        this.kycService = kycService;
    }

    /**
     * LUÔN trả 200 cho duplicate/stale — mã HTTP ở webhook là tín hiệu điều khiển
     * retry của đối tác: 4xx nghĩa là "giao thất bại" -> verifier retry vô hạn.
     */
    @PostMapping("/decision")
    public ResponseEntity<Map<String, String>> decision(@Valid @RequestBody DecisionWebhookRequest req) {
        KycService.DecisionResult result = kycService.applyDecision(
                req.submissionId(), req.decision(), req.decidedBy(), req.reason());
        if (result != KycService.DecisionResult.APPLIED) {
            log.warn("Webhook no-op: {} for submission {}", result, req.submissionId());
        }
        return ResponseEntity.ok(Map.of("result", result.name()));
    }
}
```

- [ ] **Step 3: `GlobalExceptionHandler`**

```java
package com.vng.kyc.infrastructure.web;

import com.vng.kyc.domain.InvalidKycTransitionException;
import com.vng.kyc.domain.SubmissionNotFoundException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(InvalidKycTransitionException.class)
    public ResponseEntity<Map<String, String>> invalidTransition(InvalidKycTransitionException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage()); // 409: input đúng, trạng thái không cho phép
    }

    @ExceptionHandler(SubmissionNotFoundException.class)
    public ResponseEntity<Map<String, String>> submissionNotFound(SubmissionNotFoundException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException ex) {
        return body(HttpStatus.NOT_FOUND, "Resource not found");
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Map<String, String>> lockConflict(OptimisticLockingFailureException ex) {
        return body(HttpStatus.CONFLICT, "Concurrent update, please retry");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldError() != null
                ? ex.getBindingResult().getFieldError().getDefaultMessage() : "Invalid request";
        return body(HttpStatus.BAD_REQUEST, msg);
    }

    private ResponseEntity<Map<String, String>> body(HttpStatus status, String msg) {
        return ResponseEntity.status(status).body(Map.of("error", msg));
    }
}
```

- [ ] **Step 4:** Run `mvn -q compile` → SUCCESS.
- [ ] **Step 5:** `git add kyc-service && git commit -m "feat(kyc): web layer with webhook semantics + exception mapping"`

---

## Task 9: Integration test — auth biên + idempotent qua HTTP + full flow

**Files:**
- Test: `kyc-service/src/test/java/com/vng/kyc/KycIntegrationTest.java`
- Test helper: `kyc-service/src/test/java/com/vng/kyc/support/TestSigner.java`

- [ ] **Step 1: Test helper ký HMAC (đóng vai gateway + verifier)**

```java
package com.vng.kyc.support;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Ký request test — cùng canonical hợp đồng. */
public final class TestSigner {

    public static String sign(String secret, String serviceId, String method, String path,
                              String timestamp, byte[] body) {
        try {
            String canonical = String.join("\n", serviceId, method, path, timestamp, sha256Hex(body));
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder sb = new StringBuilder();
            for (byte b : mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)))
                sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256Hex(byte[] body) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte b : md.digest(body == null ? new byte[0] : body)) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
```

- [ ] **Step 2: Integration test (MockMvc, full context + filters)**

```java
package com.vng.kyc;

import com.vng.kyc.infrastructure.persistence.SpringDataKycDecisionJpa;
import com.vng.kyc.support.TestSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "kyc.internal-hmac-secret=it-internal",
        "kyc.verifier-hmac-secret=it-verifier",
        "kyc.allowed-services=api-gateway,wallet-service"
})
@AutoConfigureMockMvc
class KycIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SpringDataKycDecisionJpa decisionJpa;

    private String now() { return Long.toString(Instant.now().getEpochSecond()); }

    private MvcResult signedPost(String path, String body, String serviceId, String secret,
                                 String roles) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String ts = now();
        var req = post(path).contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header("X-Service-Id", serviceId)
                .header("X-Timestamp", ts)
                .header("X-Signature", TestSigner.sign(secret, serviceId, "POST", path, ts, bytes));
        if (roles != null) req = req.header("X-Roles", roles);
        return mockMvc.perform(req).andReturn();
    }

    private MvcResult webhookPost(String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String ts = now();
        return mockMvc.perform(post("/kyc/webhooks/decision")
                .contentType(MediaType.APPLICATION_JSON).content(bytes)
                .header("X-Timestamp", ts)
                .header("X-Signature", TestSigner.sign("it-verifier", "verifier", "POST",
                        "/kyc/webhooks/decision", ts, bytes))).andReturn();
    }

    private String submitAndGetId(String userId) throws Exception {
        MvcResult r = signedPost("/kyc/submissions",
                "{\"userId\":\"" + userId + "\",\"documentRefs\":[\"ref-1\"]}",
                "api-gateway", "it-internal", null);
        assertEquals(201, r.getResponse().getStatus());
        String json = r.getResponse().getContentAsString();
        return json.replaceAll(".*\"submissionId\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void fullFlow_submitApproveStatus() throws Exception {
        String subId = submitAndGetId("user-flow");

        MvcResult wh = webhookPost("{\"submissionId\":\"" + subId
                + "\",\"decision\":\"APPROVE\",\"decidedBy\":\"verifier-x\",\"reason\":\"ok\"}");
        assertEquals(200, wh.getResponse().getStatus());

        String path = "/kyc/cases/user-flow/status";
        String ts = now();
        mockMvc.perform(get(path)
                        .header("X-Service-Id", "wallet-service")
                        .header("X-Timestamp", ts)
                        .header("X-Signature", TestSigner.sign("it-internal", "wallet-service", "GET", path, ts, new byte[0])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void duplicateWebhook_returns200ButOnlyOneDecisionInDb() throws Exception {
        String subId = submitAndGetId("user-dup");
        String body = "{\"submissionId\":\"" + subId
                + "\",\"decision\":\"APPROVE\",\"decidedBy\":\"v\",\"reason\":\"ok\"}";

        assertEquals(200, webhookPost(body).getResponse().getStatus());
        assertEquals(200, webhookPost(body).getResponse().getStatus()); // retry -> vẫn 200

        long count = decisionJpa.findAll().stream()
                .filter(d -> d.getSubmissionId().equals(subId)).count();
        assertEquals(1, count, "DB CHỈ CÓ đúng 1 decision — audit trail sạch");
    }

    @Test
    void webhookWithWrongSecret_is401() throws Exception {
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        String ts = now();
        mockMvc.perform(post("/kyc/webhooks/decision")
                        .contentType(MediaType.APPLICATION_JSON).content(bytes)
                        .header("X-Timestamp", ts)
                        .header("X-Signature", TestSigner.sign("WRONG", "verifier", "POST",
                                "/kyc/webhooks/decision", ts, bytes)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokeWithoutComplianceRole_is403() throws Exception {
        String subId = submitAndGetId("user-revoke");
        webhookPost("{\"submissionId\":\"" + subId
                + "\",\"decision\":\"APPROVE\",\"decidedBy\":\"v\",\"reason\":\"ok\"}");

        MvcResult noRole = signedPost("/kyc/cases/user-revoke/revoke",
                "{\"reason\":\"fraud\"}", "api-gateway", "it-internal", null);
        assertEquals(403, noRole.getResponse().getStatus());

        MvcResult withRole = signedPost("/kyc/cases/user-revoke/revoke",
                "{\"reason\":\"fraud\"}", "api-gateway", "it-internal", "compliance");
        assertEquals(200, withRole.getResponse().getStatus());
    }

    @Test
    void statusFromUnknownService_is403() throws Exception {
        String path = "/kyc/cases/u/status";
        String ts = now();
        mockMvc.perform(get(path)
                        .header("X-Service-Id", "evil-service")
                        .header("X-Timestamp", ts)
                        .header("X-Signature", TestSigner.sign("it-internal", "evil-service", "GET", path, ts, new byte[0])))
                .andExpect(status().isForbidden());
    }

    @Test
    void statusForUnknownUser_is200NotStarted() throws Exception {
        String path = "/kyc/cases/ghost/status";
        String ts = now();
        mockMvc.perform(get(path)
                        .header("X-Service-Id", "wallet-service")
                        .header("X-Timestamp", ts)
                        .header("X-Signature", TestSigner.sign("it-internal", "wallet-service", "GET", path, ts, new byte[0])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOT_STARTED"));
    }
}
```

- [ ] **Step 3:** Run `cd kyc-service && mvn -q test` → PASS toàn bộ (smoke 1 + domain 21 + service 7 + persistence 3 + hmac 3 + integration 6 ≈ 41 tests).
- [ ] **Step 4:** `git add kyc-service && git commit -m "test(kyc): integration — boundary auth, HTTP idempotency, full flow"`

---

## Định nghĩa "Done"

- `cd kyc-service && mvn -q test` xanh toàn bộ.
- `domain/` không import Spring/JPA.
- Ma trận transition đủ 20 ô (cả ô cấm).
- Webhook trùng qua HTTP → 200 hai lần, DB đúng 1 decision.
- Auth: webhook sai secret → 401; service lạ → 403; revoke thiếu role → 403, có role → 200.
- Status user chưa nộp → 200 NOT_STARTED.
- Revoke phát event qua `LoggingKycEventPublisher` (thấy trong log).

## Bước kế tiếp (plan riêng)
- SP1: wallet Stage 2 (ledger + withdraw) — tiền đề cổng KYC.
- SP3: tích hợp cổng withdraw (sync + breaker fail-closed + cache TTL + Kafka kyc.revoked).
