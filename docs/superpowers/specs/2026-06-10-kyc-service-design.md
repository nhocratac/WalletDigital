# Thiết kế: KYC Service

- **Ngày:** 2026-06-10
- **Phạm vi:** MỘT microservice — `kyc-service` (SP2 trong chuỗi KYC). Tự chạy/test độc lập, chưa cần Kafka.
- **Mục tiêu:** Học state machine, audit ledger, idempotent webhook, bảo mật đa biên (multi-boundary auth), AuthN vs AuthZ.

> Tài liệu này là "hợp đồng thiết kế" trước khi code. Mọi quyết định kèm *lý do* và *đánh đổi*. Thiết kế đi từ **nhu cầu thực tế** (requirements-first), không phải chọn công nghệ trước.

---

## 1. Nhu cầu & Ràng buộc (đã khai thác qua requirements elicitation)

KYC (*Know Your Customer*) là yêu cầu **pháp lý bắt buộc** với hệ tài chính: xác minh danh tính trước khi cho giao dịch tiền (chống rửa tiền/AML, gian lận).

| # | Yêu cầu đã chốt | Hệ quả kiến trúc |
|---|---|---|
| R1 | KYC **just-in-time**: chặn ở lần RÚT/CHUYỂN đầu tiên (nhận tiền tự do) | Cổng KYC nằm trên đường rút của wallet |
| R2 | Xác minh **bất đồng bộ**: nộp → chờ duyệt → kết quả về sau | State machine + webhook nhận kết quả |
| R3 | Wallet biết KYC qua **sync query** (compliance gate cần dữ liệu mới, fail-closed) | `GET /kyc/cases/{userId}/status` |
| R4 | KYC chết không được sập đường rút của user đã duyệt | Wallet: circuit breaker fail-closed + cache TTL (SP3) |
| R5 | **Thu hồi** KYC phải hiệu lực nhanh (gian lận) | Publish event `kyc.revoked` → wallet xoá cache (SP3) |
| R6 | **Audit trail** bất biến (compliance): ai nộp, ai duyệt, lý do, lúc nào | Ledger: KycSubmission + KycDecision append-only |
| R7 | Verifier retry webhook; user nộp lại sau khi bị từ chối | Webhook idempotent + chống quyết định lạc hậu |

### Phân rã chuỗi KYC (mỗi sub-project một spec/plan riêng)

```
SP1  wallet Stage 2  — ledger + topup/WITHDRAW + idempotency   (tiền đề: phải có "rút" để gác)
SP2  kyc-service     — TÀI LIỆU NÀY. Tự đứng độc lập.
SP3  Tích hợp cổng   — wallet gác withdraw: sync call + breaker + cache + consume kyc.revoked (cần Kafka)
```

---

## 2. Domain & State Machine (trái tim của service)

```
   (NOT_STARTED)
        │ submit
        ▼
     PENDING ──approve──►  APPROVED ──revoke──► REVOKED ──submit──► PENDING
        │                                          (nộp lại)
        └──reject──►  REJECTED ──submit──► PENDING  (nộp lại)
```

| Trạng thái | Ý nghĩa | Wallet cho rút? |
|---|---|---|
| `NOT_STARTED` | Chưa nộp gì (không có case) | ❌ |
| `PENDING` | Đã nộp, chờ duyệt | ❌ |
| `APPROVED` | Đã xác minh | ✅ |
| `REJECTED` | Bị từ chối (có lý do) | ❌ |
| `REVOKED` | Bị thu hồi (gian lận...) | ❌ |

### Mô hình dữ liệu — ledger pattern (giống wallet, vì cùng loại ràng buộc!)

```
kyc_case                       kyc_submission (BẤT BIẾN)        kyc_decision (BẤT BIẾN)
─────────────────              ─────────────────────            ───────────────────────
user_id      PK                id            PK                 id             PK
status       enum              user_id                          submission_id  FK + UNIQUE ◄─ idempotency
current_submission_id          document_refs (text)             type    APPROVE|REJECT|REVOKE
version      (@Version)        submitted_at                     decided_by
                                                                reason
                                                                decided_at
```

- `kyc_case` = trạng thái HIỆN TẠI (đọc nhanh, cho wallet hỏi). Submissions/decisions = sổ cái audit.
- `UNIQUE(submission_id)` trên decision = chốt chặn idempotency Ở TẦNG DB (không chỉ logic).
- PII: domain chỉ giữ **document refs** (tham chiếu), KHÔNG giữ file giấy tờ thật — giảm bề mặt rò rỉ.

### Cấu trúc code (Clean Architecture — domain DÀY vì nghiệp vụ thật)

```
com.vng.kyc
├── KycApplication.java
├── domain/                          ← thuần Java, KHÔNG Spring/JPA
│   ├── KycCase.java                 · luật transition ÉP TRONG MODEL:
│   │     submit()  : NOT_STARTED/REJECTED/REVOKED → PENDING, else ném
│   │     approve() : CHỈ PENDING → APPROVED, else ném
│   │     reject()  : CHỈ PENDING → REJECTED, else ném
│   │     revoke()  : CHỈ APPROVED → REVOKED, else ném
│   ├── KycSubmission.java           · bất biến
│   ├── KycDecision.java             · bất biến
│   ├── KycStatus.java               · enum 5 trạng thái
│   ├── KycCaseRepository.java       · PORT
│   ├── KycEventPublisher.java       · PORT — publish(kyc.revoked). SP2: adapter no-op/log; SP3: Kafka.
│   └── exceptions: InvalidKycTransitionException, SubmissionNotFoundException
├── application/
│   └── KycService.java              · use cases: submit, applyDecision (webhook), revoke, getStatus
└── infrastructure/
    ├── persistence/  (entities + adapters, map domain↔JPA như wallet)
    ├── security/     (HMAC nội bộ + HMAC verifier riêng + role check)
    ├── events/LoggingKycEventPublisher.java   · adapter no-op/log cho SP2
    └── web/          (controllers + dto + GlobalExceptionHandler)
```

**Nguyên tắc:** luật chuyển trạng thái sống TRONG `KycCase` (make illegal states unrepresentable), không rải `if` ở controller/service.

---

## 3. Actors, Endpoints & Luồng

| Actor | Endpoint | Auth |
|---|---|---|
| User (qua gateway) | `POST /kyc/submissions` `{userId, documentRefs[]}` → 201 `{submissionId}` | HMAC nội bộ (gateway ký; X-User-Id từ JWT) |
| Verifier (NGOÀI hệ) | `POST /kyc/webhooks/decision` `{submissionId, decision, reason}` | **HMAC secret RIÊNG của verifier** + timestamp |
| wallet-service | `GET /kyc/cases/{userId}/status` → `{userId, status}` | HMAC nội bộ (allowlist wallet-service) |
| Compliance/Admin | `POST /kyc/cases/{userId}/revoke` `{reason}` | HMAC nội bộ + **role check** (X-Roles: compliance) |

### Luồng webhook decision (idempotent + chống lạc hậu)

```
verifier POST /kyc/webhooks/decision {submissionId, decision} + HMAC verifier
   │
   ▼ verify chữ ký (secret verifier) + |now - timestamp| < 5 phút
   ▼ submission tồn tại?            không → 404
   ▼ ĐÃ có decision cho submissionId?   có → 200 OK no-op  ◄─ IDEMPOTENT (verifier retry)
   ▼ submissionId == case.currentSubmissionId?  không → 200 OK no-op + log warn ◄─ LẠC HẬU (user đã nộp lại)
   ▼ @Transactional: ghi KycDecision (bất biến) + case.approve()/reject()  (optimistic lock @Version)
   ▼ 200 OK
```

### Luồng revoke

```
admin POST /kyc/cases/{u}/revoke + HMAC nội bộ + role compliance
   → case.revoke() (chỉ APPROVED→REVOKED) + ghi KycDecision(REVOKE)
   → KycEventPublisher.publish(kyc.revoked)   ← SP2: log; SP3: Kafka → wallet xoá cache
```

---

## 4. Bảo mật đa biên & Xử lý lỗi

### Bốn biên tin cậy — bốn cơ chế

| Biên | Cơ chế | Lý do |
|---|---|---|
| User → submit | Qua gateway (JWT verified) → HMAC nội bộ | User không gọi thẳng KYC |
| Verifier → webhook | HMAC **secret riêng** + chống replay | Verifier ở NGOÀI — secret segmentation: lộ secret đối tác không lan vào nội bộ |
| Wallet → status | HMAC nội bộ, allowlist | Chuẩn gọi nội bộ như hệ đã có |
| Admin → revoke | HMAC nội bộ + **role** | AuthN chưa đủ — cần AuthZ: không phải caller nội bộ nào cũng được revoke |

### Bảng lỗi → HTTP

| Tình huống | HTTP |
|---|---|
| HMAC sai/thiếu/hết hạn (mọi biên) | 401 |
| Ngoài allowlist / thiếu role compliance | 403 |
| Submit khi đang PENDING/APPROVED | 409 |
| Webhook cho submission không tồn tại | 404 |
| Webhook TRÙNG (đã có decision) | **200 no-op** ◄ mã HTTP ở webhook là tín hiệu điều khiển retry của đối tác |
| Webhook LẠC HẬU (submission cũ) | **200 no-op** + log warn |
| Revoke user chưa APPROVED | 409 |
| Status user chưa nộp | **200 `{status: NOT_STARTED}`** ◄ trạng thái nghiệp vụ hợp lệ, không phải lỗi |
| Xung đột optimistic lock | 409 |
| Input sai | 400 |

---

## 5. Chiến lược kiểm thử

```
Tầng 1 — Unit domain (thuần Java): MA TRẬN ĐẦY ĐỦ 5 trạng thái × 4 hành động = 20 test
  · Test CẢ Ô CẤM: bug compliance là "transition cấm mà đi được" (vd REJECTED→APPROVED không qua duyệt)
Tầng 2 — Service test (fake repo + fake publisher):
  · submit tạo submission bất biến + PENDING
  · ⭐ idempotent: cùng submissionId 2 lần → lần 2 no-op, KHÔNG decision thứ hai
  · ⭐ lạc hậu: decision của submission cũ → bỏ qua, trạng thái giữ nguyên
  · revoke → fake publisher nhận kyc.revoked (port trả công: test event không cần Kafka)
Tầng 3 — Integration (Spring + H2 + MockMvc):
  · full flow: submit → webhook approve → status APPROVED
  · resubmit sau REJECTED; webhook submission MỚI mới có hiệu lực
  · auth từng biên: webhook sai chữ ký → 401; revoke thiếu role → 403; ngoài allowlist → 403
  · ⭐ webhook trùng qua HTTP: 200 cả 2 lần, DB CHỈ CÓ 1 decision (SELECT đếm)
  · concurrency: 2 webhook đua → optimistic lock, 1 thắng
```

Test quan trọng nhất: **"webhook trùng → DB chỉ có đúng 1 decision"** — cam kết audit trail sạch và trạng thái không lật 2 lần.

---

## 6. Nợ kỹ thuật & YAGNI

**Để SP3 / stage sau:**
- Kafka adapter cho `KycEventPublisher` (giờ: LoggingKycEventPublisher).
- Tích hợp cổng withdraw ở wallet (breaker fail-closed + cache TTL + consume revoke).
- Đẩy hồ sơ sang verifier thật (giờ: verifier giả lập gọi webhook trong test/e2e).

**Cố tình chưa làm:**
- KYC tiers/hạn mức (giờ: binary APPROVED/not).
- Lưu trữ file giấy tờ thật + mã hoá (chỉ giữ refs).
- Sanctions screening, expiry/re-KYC định kỳ.
- `ddl-auto=update` vẫn là nợ kỹ thuật chung (Flyway sau).

---

## 7. Lộ trình triển khai đề xuất

1. Domain: KycCase + transitions + ma trận 20 unit test.
2. Submission/Decision + KycService (submit/applyDecision/revoke/getStatus) + fake-based tests.
3. Persistence adapters (JPA, UNIQUE submission_id) + @DataJpaTest.
4. Security: HMAC nội bộ (tái dùng pattern wallet/gateway) + HMAC verifier riêng + role check.
5. Web layer + GlobalExceptionHandler + integration tests (auth biên, idempotent qua HTTP, concurrency).
6. LoggingKycEventPublisher + chốt "Done".
