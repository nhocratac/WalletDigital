# Transactional Outbox Implementation Plan (kyc-service)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Steps dùng checkbox (`- [ ]`).

**Goal:** Không mất event `kyc.revoked` giữa commit DB ↔ publish Kafka. Ghi event vào bảng `outbox` trong CÙNG transaction `revoke()`; một relay `@Scheduled` đẩy outbox → Kafka (at-least-once); consumer wallet đã idempotent (D9) nuốt trùng.

**Architecture:** Theo design `docs/superpowers/specs/2026-06-23-transactional-outbox-design.md` (O1–O8). **Cách gọn nhất — swap adapter:** giữ port `KycEventPublisher.publishKycRevoked(userId, reason)`; `revoke()` **không đổi** (vẫn gọi `eventPublisher.publishKycRevoked` *trong* `@Transactional`), nhưng đổi impl active từ `KafkaKycEventPublisher` (send thẳng) → **`OutboxKycEventPublisher`** (INSERT outbox row — tham gia tx của revoke). Relay đọc outbox → Kafka.

**Tech Stack:** Java 25, Spring Boot 3.4.4, JPA, H2 (kyc dùng **ddl-auto=update** → bảng outbox tự tạo từ @Entity, KHÔNG cần Flyway), spring-kafka + EmbeddedKafka (test), `@Scheduled`.

**⚠️ LƯU Ý DRIFT:** `KycService.revoke()` (@Transactional, dòng ~77) gọi `eventPublisher.publishKycRevoked(userId, reason)` NGAY trong tx (dual-write). Có 2 adapter: `KafkaKycEventPublisher` (@ConditionalOnProperty kafka-enabled) + `LoggingKycEventPublisher`. Outbox **thay** Kafka-trực-tiếp ở đường revoke. Wallet `KycRevokedConsumer` idempotent (D9) — KHÔNG đụng. 63 test kyc + 262 wallet phải xanh.

---

## Quyết định khoá
1. **Swap adapter, giữ port:** `OutboxKycEventPublisher implements KycEventPublisher` — `publishKycRevoked` = `outboxRepository.save(PENDING row)` (KHÔNG send Kafka). Chạy trong `@Transactional` của `revoke()` → **nguyên tử** với REVOKED (O1). `revoke()` code KHÔNG đổi.
2. **Relay** = bean riêng đọc outbox → dùng `KafkaTemplate` send thẳng `(topic, key=aggregate, payload)` → mark SENT. (KafkaKycEventPublisher cũ: bỏ khỏi đường revoke; relay tự send hoặc tái dùng — chọn relay send generic để hỗ trợ nhiều loại event sau.)
3. **outbox table** (ddl-auto tự tạo): `id PK↑, aggregate, topic, payload(JSON), status(PENDING/SENT), created_at, sent_at`. Poll `ORDER BY id` (O7).
4. **At-least-once:** relay crash giữa send & mark → row vẫn PENDING → gửi lại → trùng → consumer idempotent (O3/O4). KHÔNG cố exactly-once.
5. **Bật/tắt:** outbox publisher + relay bật khi `kyc.events.kafka-enabled=true` (như hiện tại); test/dev cũ (logging) không đổi. Relay interval config `kyc.outbox.relay-interval-ms`.
6. **Purge** `@Scheduled` xoá SENT cũ > TTL (`kyc.outbox.ttl-days`).

---

## Cấu trúc thay đổi

```
kyc-service/src/main/java/com/vng/kyc/
├── domain/OutboxEvent.java                 (Create: record/entity domain — hoặc chỉ entity)
├── infrastructure/outbox/
│   ├── OutboxEventEntity.java              (Create: @Entity → ddl-auto tạo bảng)
│   ├── OutboxRepository.java + JpaOutboxRepository (Create)
│   ├── OutboxKycEventPublisher.java        (Create: implements KycEventPublisher → INSERT outbox; active khi kafka-enabled)
│   ├── OutboxRelay.java                    (Create: @Scheduled poll PENDING → KafkaTemplate.send → mark SENT)
│   └── OutboxPurge.java                    (Create: @Scheduled xoá SENT cũ > TTL)
└── infrastructure/events/KafkaKycEventPublisher.java  (Modify: gỡ @ConditionalOnProperty active / hoặc giữ làm dùng-bởi-relay — chốt: relay dùng KafkaTemplate trực tiếp, KafkaKycEventPublisher có thể bỏ khỏi wiring revoke)
src/main/resources/application.yml          (+ kyc.outbox.relay-interval-ms, ttl-days)
```

---

## Task 1: Bảng outbox + entity + repo (O1 phần lưu)

**Files:** Create `infrastructure/outbox/{OutboxEventEntity, OutboxRepository, JpaOutboxRepository}` + `domain/OutboxEvent` (nếu tách domain); (test) `JpaOutboxRepositoryTest`.

- [ ] **Step 1: Test:** save PENDING row → findPending(limit) trả theo `id` tăng dần; markSent(id) đổi status+sent_at; findPending KHÔNG trả SENT.
- [ ] **Step 2:** `cd kyc-service && mvn -q test -Dtest=JpaOutboxRepositoryTest` → FAIL.
- [ ] **Step 3:** `OutboxEventEntity` (`@Entity` name `outbox`: id, aggregate, topic, payload, status enum, createdAt, sentAt) → ddl-auto=update tự tạo. Repo: `save`, `findPending(int limit)` (`WHERE status=PENDING ORDER BY id`), `markSent(id)`, `deleteSentOlderThan(Instant)`.
- [ ] **Step 4:** `mvn -q test` → xanh (chỉ thêm — không đụng luồng).
- [ ] **Step 5:** `git commit -m "feat(kyc): outbox table + repository (O1 storage)"`

---

## Task 2: `OutboxKycEventPublisher` — ghi outbox trong tx revoke (O1 nguyên tử)

**Files:** Create `infrastructure/outbox/OutboxKycEventPublisher.java`; Modify wiring (active khi kafka-enabled, thay KafkaKycEventPublisher ở đường revoke); (test) `OutboxKycEventPublisherTest` + `KycServiceOutboxIntegrationTest`.

- [ ] **Step 1: Test:**
  - `revoke(userId)` → `kyc_case=REVOKED` VÀ **outbox có 1 row PENDING** (topic=kyc.revoked, aggregate=userId, payload chứa userId+reason) — trong CÙNG tx.
  - ⭐ **nguyên tử:** giả lỗi sau khi ghi outbox (vd ném trong cùng tx) → rollback → **KHÔNG có REVOKED VÀ KHÔNG có outbox row** (cả hai cùng biến mất).
  - đường revoke KHÔNG gọi Kafka trực tiếp nữa (verify KafkaTemplate không bị gọi trong revoke).
- [ ] **Step 2:** `mvn -q test` → FAIL.
- [ ] **Step 3:** `OutboxKycEventPublisher implements KycEventPublisher`: `publishKycRevoked(userId, reason)` = `outboxRepository.save(new OutboxEventEntity(userId, "kyc.revoked", toJson(userId,reason), PENDING, now))`. Đánh dấu là bean active khi `kyc.events.kafka-enabled=true` (thay `KafkaKycEventPublisher` ở đường này). `revoke()` KHÔNG đổi (vẫn gọi `eventPublisher.publishKycRevoked` trong @Transactional → giờ ghi outbox cùng tx).
- [ ] **Step 4:** `mvn -q test` → xanh (63 test cũ — chú ý test nào verify "revoke publishes kafka" giờ verify "revoke writes outbox"; drift có chủ đích).
- [ ] **Step 5:** `git commit -m "feat(kyc): OutboxKycEventPublisher writes event in revoke tx (atomic, O1)"`

---

## Task 3: `OutboxRelay` — đẩy outbox → Kafka (O2, O3, O7)

**Files:** Create `infrastructure/outbox/OutboxRelay.java`; (test) `OutboxRelayTest` (EmbeddedKafka).

- [ ] **Step 1: Test:**
  - PENDING row → relay chạy → `KafkaTemplate.send(topic, key=aggregate, payload)` (EmbeddedKafka nhận được) → row → SENT.
  - poll theo `id` tăng dần (O7: thứ tự).
  - ⭐ **at-least-once:** giả crash giữa send & markSent (vd markSent ném / không chạy) → row vẫn PENDING → vòng relay sau gửi LẠI (consumer sẽ nhận trùng — OK).
  - không có PENDING → relay no-op.
- [ ] **Step 2:** `mvn -q test -Dtest=OutboxRelayTest` → FAIL.
- [ ] **Step 3:** `OutboxRelay` `@Scheduled(fixedDelayString="${kyc.outbox.relay-interval-ms:2000}")` (bật khi kafka-enabled): `findPending(batch)` → mỗi row `kafkaTemplate.send(topic, aggregate, payload).get()` (chờ ack) → `markSent`; send fail → để PENDING (lần sau). `@EnableScheduling`.
- [ ] **Step 4:** `mvn -q test` → xanh.
- [ ] **Step 5:** `git commit -m "feat(kyc): OutboxRelay polls PENDING -> Kafka -> mark SENT (at-least-once, O2,O3,O7)"`

---

## Task 4: `OutboxPurge` — bảng không phình (O5)

**Files:** Create `infrastructure/outbox/OutboxPurge.java`; (test) `OutboxPurgeTest`.

- [ ] **Step 1: Test:** row SENT + sent_at cũ hơn TTL → xoá; SENT mới / PENDING → giữ.
- [ ] **Step 2:** `mvn -q test` → FAIL → cài `@Scheduled` `deleteSentOlderThan(now - ttlDays)` (config `kyc.outbox.ttl-days:7`, bật bằng property) → PASS.
- [ ] **Step 3:** `git commit -m "feat(kyc): OutboxPurge deletes old SENT rows by TTL (O5)"`

---

## Task 5: Regression consumer + e2e (không mất event)

**Files:** (test) regression wallet `KycRevokedConsumerTest` (trùng vô hại — có thể đã có); Modify `e2e/` (chứng minh không mất).

- [ ] **Step 1: Regression (wallet):** consumer nhận `kyc.revoked` 2 lần (relay gửi trùng) → evict cache 2 lần vô hại (D9) — đã có/bổ sung assertion.
- [ ] **Step 2: e2e:** revoke → relay đẩy (đợi ~interval) → wallet evict cache → withdraw **403**. Chứng minh không-mất: tạm **TẮT relay** (`relay-interval` lớn / flag off) sau revoke → event nằm PENDING trong outbox (chưa gửi) → DB đã REVOKED; **BẬT relay** → event đẩy → wallet 403. (Mô phỏng "crash trước publish": event vẫn còn, không mất.)
- [ ] **Step 3:** chạy 3 service + Kafka; xác nhận. Dọn (kill PID giữ cổng).
- [ ] **Step 4:** `git commit -m "test(outbox): duplicate-safe consumer (D9) + e2e event-not-lost"`

---

## Nợ kỹ thuật & YAGNI
- **CDC (Debezium)** thay polling — khi cần realtime; cùng hạ tầng SP7-OLAP.
- Outbox cho **wallet** (nếu wallet phát event) → relay per-tenant-schema (fleet, SP5/SP7).
- Dead-letter cho outbox row gửi mãi không được (poison) — retry vô hạn + alert hiện tại; nâng sau.
- kyc dùng ddl-auto (nợ sẵn) → khi Flyway-hóa kyc thì outbox vào migration.

## Checklist Done
- [ ] revoke() → REVOKED + outbox PENDING trong CÙNG tx; rollback → KHÔNG cả hai (nguyên tử).
- [ ] revoke KHÔNG send Kafka trực tiếp nữa.
- [ ] relay PENDING → Kafka → SENT; at-least-once (crash → gửi lại); thứ tự theo id.
- [ ] consumer trùng vô hại (D9); purge SENT cũ.
- [ ] e2e: event không mất (TẮT relay → còn PENDING → BẬT → đẩy → 403).
- [ ] 63 kyc + 262 wallet xanh + test mới; git clean.
