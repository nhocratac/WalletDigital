# Observability Nấc 1 — TraceId + MDC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Steps dùng checkbox (`- [ ]`).

**Goal:** traceId nối liền 3 service (gateway/wallet/kyc) qua HTTP + Kafka + worker, vào **MDC** để mọi log tự mang `[%X{traceId}]`. Nấc 1 (correlation ID).

**Architecture:** Theo design `docs/superpowers/specs/2026-06-22-observability-traceid-mdc-design.md` (OB1–OB8). Continue-or-generate ở mọi entry; clear MDC trong finally; outbound `ClientHttpRequestInterceptor`; Kafka header; worker sinh root.

**Tech Stack:** Java 25, Spring Boot 3.4.4, SLF4J/Logback MDC, OncePerRequestFilter, RestClient interceptor, spring-kafka, H2 + Testcontainers MySQL, MockWebServer + EmbeddedKafka (test).

**⚠️ LƯU Ý DRIFT:** đụng **3 module**. gateway đã có `TraceIdFilter` (request-attribute, chưa MDC). wallet có chuỗi filter Stage4: `HmacVerifyFilter @Order(0)` → `TenantFilter @Order(1)` → TraceIdFilter mới phải chạy **TRƯỚC** cả hai (đặt `@Order(Ordered.HIGHEST_PRECEDENCE)` / số âm). Toàn bộ 335 test phải xanh.

---

## Quyết định khoá
1. **TraceIdFilter mỗi service**: đọc `X-Trace-Id` → thiếu thì `UUID.randomUUID()` → `MDC.put("traceId", id)`; `finally { MDC.clear() }`. Header const dùng chung tên `X-Trace-Id` (khớp gateway hiện tại).
2. **Log pattern** `[%X{traceId}]` trong `application.properties` (`logging.pattern.level` hoặc `logging.pattern.console`) cả 3 service.
3. **Wallet filter order**: TraceIdFilter `HIGHEST_PRECEDENCE` (trước HmacVerifyFilter @Order(0)) → log 401 vẫn có traceId.
4. **Outbound interceptor** wallet: `ClientHttpRequestInterceptor` đọc `MDC.get("traceId")` → set header `X-Trace-Id`; đăng ký trên RestClient của RestKycGate + RestBankClient.
5. **Kafka**: producer thêm header `traceId` (bytes) vào ProducerRecord; consumer đọc header → MDC (sinh nếu thiếu) → clear finally.
6. **Worker**: reconciliation + idempotency-purge — đầu mỗi vòng `MDC.put(traceId, UUID)`, `finally MDC.clear()` (lồng trong vòng per-tenant đã có).
7. traceId = UUID opaque; KHÔNG nhúng tenantId/userId.

---

## Task 1: wallet TraceIdFilter + MDC + log pattern (OB1–OB3)

**Files:** Create `wallet .../observability/TraceIdFilter.java`; Modify `application.properties`; (test) `TraceIdFilterTest`.

- [ ] **Step 1: Test:** có `X-Trace-Id: abc` → trong chain `MDC.get("traceId")`=="abc"; không header → MDC có UUID (không rỗng); sau chain (finally) MDC trống; chain ném exception vẫn clear; thread-reuse: gọi A rồi gọi B (không header) → B không mang trace A.
- [ ] **Step 2:** `cd wallet-service && mvn -q test -Dtest=TraceIdFilterTest` → FAIL.
- [ ] **Step 3:** `TraceIdFilter extends OncePerRequestFilter`, `@Order(Ordered.HIGHEST_PRECEDENCE)` (trước HmacVerifyFilter); try{ set MDC } finally{ MDC.clear() }; cũng set response header `X-Trace-Id`. `application.properties`: `logging.pattern.console=... [%X{traceId}] ...`.
- [ ] **Step 4:** `mvn -q test` → xanh (toàn wallet).
- [ ] **Step 5:** `git commit -m "feat(wallet): TraceIdFilter -> MDC (continue-or-generate, clear-in-finally) + log pattern (OB1-3)"`

---

## Task 2: gateway MDC + kyc TraceIdFilter (OB1, OB2)

**Files:** Modify gateway `TraceIdFilter` (+ MDC) + `application.properties`; Create kyc `TraceIdFilter` + Modify kyc `application.properties`; (test) gateway + kyc filter tests.

- [ ] **Step 1: Test:** gateway: traceId vào MDC trong chain, clear sau. kyc: continue-or-generate → MDC + clear (như wallet).
- [ ] **Step 2:** build 2 module → FAIL.
- [ ] **Step 3:** gateway `TraceIdFilter`: thêm `MDC.put/clear` (giữ logic forward sẵn) + log pattern. kyc: `TraceIdFilter` mới (mirror wallet) + log pattern. (kyc filter chạy trước InternalAuthFilter @Order(1) → @Order(0)/HIGHEST.)
- [ ] **Step 4:** `cd api-gateway && mvn -q test` + `cd kyc-service && mvn -q test` → xanh.
- [ ] **Step 5:** `git commit -m "feat(gateway,kyc): traceId into MDC + log pattern (OB1,OB2)"`

---

## Task 3: Outbound HTTP interceptor (wallet) (OB4)

**Files:** Create `wallet .../observability/TraceIdClientInterceptor.java`; Modify RestClient build ở `RestKycGate`/`RestBankClient` (đăng ký interceptor); (test) `TraceIdClientInterceptorTest` (MockWebServer).

- [ ] **Step 1: Test:** MDC có traceId=abc → gọi qua RestClient (RestKycGate/RestBankClient) → request đi ra MockWebServer **có header `X-Trace-Id: abc`**; MDC trống → không set (hoặc sinh — chốt: chỉ forward nếu có).
- [ ] **Step 2:** `mvn -q test -Dtest=TraceIdClientInterceptorTest` → FAIL.
- [ ] **Step 3:** `ClientHttpRequestInterceptor`: `intercept` → `String t = MDC.get("traceId"); if (t!=null) request.getHeaders().add("X-Trace-Id", t)`; đăng ký `.requestInterceptor(...)` khi build RestClient ở cả hai adapter.
- [ ] **Step 4:** `mvn -q test` → xanh.
- [ ] **Step 5:** `git commit -m "feat(wallet): outbound RestClient interceptor propagates X-Trace-Id from MDC (OB4)"`

---

## Task 4: Kafka header propagation (OB5)

**Files:** Modify kyc `KafkaKycEventPublisher` (set header traceId); Modify wallet `KycRevokedConsumer` (đọc header → MDC, clear finally); (test) EmbeddedKafka.

- [ ] **Step 1: Test:** kyc publish kyc.revoked với MDC traceId=abc → ProducerRecord có header `traceId`=abc; wallet consume → trong handler `MDC.get("traceId")`=abc, sau handler clear. message KHÔNG header → consumer sinh mới (không lỗi).
- [ ] **Step 2:** build → FAIL.
- [ ] **Step 3:** producer: `record.headers().add("traceId", mdcTrace.getBytes())`. consumer: đọc header `traceId` → MDC.put (thiếu → UUID) → xử lý → finally MDC.clear.
- [ ] **Step 4:** `cd kyc-service && mvn -q test` + `cd wallet-service && mvn -q test` → xanh.
- [ ] **Step 5:** `git commit -m "feat(kyc,wallet): propagate traceId via Kafka record header (OB5)"`

---

## Task 5: Worker sinh root traceId (OB6)

**Files:** Modify wallet `ReconciliationWorker`/`MultiTenantReconciliationRunner` + `IdempotencyPurgeWorker` (set/clear MDC per vòng); (test) bổ sung.

- [ ] **Step 1: Test:** worker chạy một vòng → có MDC traceId (UUID) trong lúc xử lý; clear sau mỗi tenant; mỗi vòng/tenant một traceId mới (root). (assert qua log capture hoặc spy.)
- [ ] **Step 2:** build → FAIL.
- [ ] **Step 3:** trong vòng lặp per-tenant (đã có set/clear TenantContext) → thêm `MDC.put("traceId", UUID)` đầu, `MDC.clear()`/remove cuối (cẩn thận không xoá nhầm TenantContext — MDC.remove("traceId")).
- [ ] **Step 4:** `mvn -q test` → xanh.
- [ ] **Step 5:** `git commit -m "feat(wallet): workers generate root traceId per run into MDC (OB6)"`

---

## Task 6: e2e — sợi chỉ liền xuyên 3 service

**Files:** (test) integration nhẹ; Modify `e2e/` (in/log traceId).

- [ ] **Step 1:** e2e: gọi withdraw qua gateway với header `X-Trace-Id: e2e-trace-xyz` → sau khi chạy, `grep "e2e-trace-xyz"` trong log gateway + wallet + kyc đều ra (sợi chỉ liền). (hoặc lấy traceId từ response header gateway.)
- [ ] **Step 2:** chạy 3 service + Kafka; xác nhận grep ra cùng traceId ở cả 3. Dọn (kill PID giữ cổng).
- [ ] **Step 3:** `git commit -m "test(obs): one traceId threads through all 3 services (gateway+wallet+kyc)"`

---

## Nợ kỹ thuật & YAGNI
- OTel/span (Nấc 2) — SP riêng; propagation header tái dùng (đổi `X-Trace-Id`→`traceparent`).
- Gộp interceptor traceId + HMAC (shared-hmac module) — chờ parent POM.
- MDC key phụ tenantId/userId để lọc — thêm sau (kèm kỷ luật PII).

## Checklist Done
- [ ] mỗi service: continue-or-generate traceId → MDC; clear finally; thread-reuse không rò.
- [ ] mọi dòng log có `[%X{traceId}]` (3 service).
- [ ] outbound HTTP (wallet→kyc/bank) + Kafka (kyc→wallet) truyền traceId; worker sinh root.
- [ ] wallet: TraceIdFilter chạy TRƯỚC HmacVerifyFilter (log 401 có traceId).
- [ ] e2e: một traceId xuyên cả 3 service.
- [ ] 335 test cũ xanh + test mới; git clean.
