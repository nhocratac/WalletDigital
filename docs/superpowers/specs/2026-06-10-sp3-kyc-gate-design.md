# Thiết kế: SP3 — Cổng KYC trên Withdraw (tích hợp wallet ↔ kyc ↔ Kafka)

- **Ngày:** 2026-06-10
- **Phạm vi:** Tích hợp — sửa `wallet-service` (cổng KYC + cache + breaker + Kafka consumer) và `kyc-service` (Kafka producer thay LoggingKycEventPublisher). KHÔNG tạo service mới.
- **Tiền đề:** SP1 ✅ (wallet có withdraw + ledger) · SP2 ✅ (kyc-service có status + revoke + event port).
- **Mục tiêu học:** Circuit Breaker, cache invalidation qua event, Kafka (producer/consumer), tích hợp đa-service, transaction boundary, IDOR/AuthZ.

> **Nguồn gốc tài liệu này:** toàn bộ 9 quyết định cốt lõi được CHÍNH NGƯỜI HỌC suy ra trong phiên brainstorm Socratic (anh chỉ hỏi & phản biện). Mỗi quyết định ghi kèm "lý do đã tự suy ra". Các con số cấu hình (TTL, ngưỡng breaker...) là đề xuất của Claude để duyệt.

---

## 1. Bối cảnh & Vấn đề

Yêu cầu gốc (R1, chuỗi KYC): *user phải KYC APPROVED mới được RÚT tiền; nhận/nạp tiền tự do (just-in-time KYC)*.

SP1 đã cho wallet lệnh `withdraw`. SP2 đã cho kyc-service trả lời `GET /kyc/cases/{userId}/status`. SP3 nối hai mảnh — và trên đường đi phát hiện **wallet còn thiếu nền tảng định danh**: ví không gắn với user nào (chỉ có `ownerName` — tên hiển thị, không phải khoá), nghĩa là (a) không có gì để hỏi KYC, (b) tồn tại lỗ hổng **IDOR**: bất kỳ ai cũng rút được từ bất kỳ ví nào nếu đoán đúng id.

```
            Người dùng ──JWT──► [api-gateway] ──HMAC + X-User-Id──► [wallet-service]
                                                                        │   ▲
                                              GET /kyc/cases/{u}/status │   │ cache APPROVED (TTL ngắn)
                                              (sync + circuit breaker)  ▼   │
                                                                    [kyc-service]
                                                                        │
                                              Kafka topic kyc.revoked   │ publish khi compliance revoke
                                    [wallet consumer] ◄─────────────────┘
                                    xoá cache + quét ledger compensation
```

---

## 2. Bảng quyết định (9 quyết định từ phiên Socratic)

| # | Quyết định | Lý do (đã tự suy ra) |
|---|---|---|
| D1 | Ví gắn **`userId`**, gán lúc tạo từ header **`X-User-Id`** (gateway bóc từ claim JWT, ký HMAC). KHÔNG nhận userId từ request body. | `ownerName` là tên hiển thị, không phải khoá. Client tự khai userId thì user A mạo danh user B được. Cùng pattern `X-Tenant-Id` đã duyệt ở gateway — JWT không bao giờ tới wallet. |
| D2 | AuthZ bằng **scoped query**: mọi truy cập ví đều `WHERE user_id = :userId AND id = :walletId`. | Nhét quyền sở hữu VÀO câu truy vấn — không tồn tại đường code lấy nhầm ví người khác, không ai "quên check" được (make illegal states unrepresentable). |
| D3 | Sai chủ → trả **404** (không phải 403) + **audit log** WARN đầy đủ chi tiết phía server. | 403 xác nhận "ví tồn tại" → kẻ dò quét enumerate được. 404 giấu sự tồn tại (GitHub làm vậy với repo private). Response cho client ≠ ghi nhận nội bộ: hai kênh, hai mục đích. |
| D4 | Gọi KYC **NGOÀI** transaction DB: check trước, mở transaction sau. | Transaction mở = chiếm 1 connection của pool (Hikari mặc định ~10). HTTP chậm bên trong transaction → pool cạn → cả service chết theo (connection pool exhaustion). Quy tắc vàng: *no remote calls inside a DB transaction*. |
| D5 | Khe hở thời gian check-KYC ↔ commit-tiền: **chấp nhận** (bản chất hệ phân tán, không triệt tiêu được) + **thu nhỏ** bằng event revoke + **bù** bằng compensation từ ledger. | Revoke từ compliance tới hệ thống luôn mất thời gian, check trong transaction cũng không đóng được khe. Detect & compensate thay vì prevent — ledger bất biến (Stage 2) chính là nền cho compensation. |
| D6 | Cache **CHỈ trạng thái APPROVED**, TTL ngắn. KHÔNG cache PENDING/NOT_STARTED/REJECTED/REVOKED. | Phân tích reversible vs irreversible: stale-PENDING hại user nhưng hoàn nguyên được (tiền còn trong ví, retry); stale-APPROVED mất tiền vĩnh viễn — NHƯNG chiều nguy hiểm này được đậy nắp bằng event `kyc.revoked` xoá cache chủ động. Cache chiều dương + event vô hiệu hoá chiều dương = cặp khớp nhau. Hit-rate: người rút lặp lại nhiều nhất là user APPROVED. Không cache trạng thái âm → user vừa được duyệt rút được NGAY. |
| D7 | Hợp đồng lỗi: KYC nói "không APPROVED" → **403**. Breaker mở + cache miss → **503 + `Retry-After`** (fail-closed). | "Anh không đủ điều kiện" ≠ "tôi không kiểm tra được lúc này" — mã lỗi là lời hứa ngữ nghĩa, trả 403 lúc KYC chết là nói dối client (user đi nộp lại hồ sơ oan). 503 chứ không phải 504: breaker mở = còn chẳng gọi downstream, không có gì "timeout". `Retry-After` là header CHUẨN (không có tiền tố X-). |
| D8 | Kafka: producer `acks=all`; consumer dựa **consumer group + offset** — wallet chết lúc event phát thì event chờ trong log, sống lại đọc tiếp. | Kafka là sổ ghi bền (durable log), khác HTTP bắn-vào-service-chết-là-mất. |
| D9 | Consumer **KHÔNG cần dedup**: hành động "xoá cache user X" là **tự nhiên idempotent** — xoá 2 lần vô hại. | Idempotency 2 con đường: thao tác tự nhiên idempotent (xoá/set) thì khỏi cần máy móc; chỉ thao tác cộng/trừ mới cần bảng dedup (Stage 2). Vác dedup vào đây là over-engineering. |

---

## 3. Định danh & Sở hữu (sửa lỗ hổng IDOR)

### 3.1 Thay đổi domain & dữ liệu

```
wallet (bảng) — THÊM:
  user_id   VARCHAR  NOT NULL (với ví mới)   ◄─ khoá định danh chủ ví
  -- giữ ownerName làm display name thuần tuý
```

- `Wallet.createNew(ownerName)` → `Wallet.createNew(userId, ownerName)`.
- `POST /wallets`: controller đọc `X-User-Id` (KHÔNG đọc từ body), truyền xuống service.
- Dữ liệu cũ (ví tạo trước SP3, user_id null): môi trường học dùng H2 in-memory nên không có dữ liệu cũ thật; ghi nhận nếu là production phải có data migration backfill.

### 3.2 Scoped access — áp cho MỌI endpoint đụng ví

IDOR không chỉ ở withdraw — `GET /wallets/{id}`, `topup`, `transactions` cũng đang hở. Sửa **đồng loạt**:

| Endpoint | Trước | Sau |
|---|---|---|
| `GET /wallets/{id}` | findById(id) | findByIdAndUserId(id, userId từ X-User-Id) |
| `POST /wallets/{id}/topup` | như trên | như trên |
| `POST /wallets/{id}/withdraw` | như trên | như trên + CỔNG KYC |
| `GET /wallets/{id}/transactions` | như trên | như trên |

Port domain đổi: `findById(Long id)` → `findByIdAndUserId(Long id, String userId)` (giữ nguyên tắc: quyền sở hữu nằm TRONG truy vấn).

Không khớp → ném `WalletNotFoundException` (tái dùng — cùng 404 với ví không tồn tại, đúng D3) + log WARN: `AUDIT forbidden-or-missing wallet access: walletId={}, callerUserId={}, traceId={}`.

### 3.3 Giả định tin cậy (ghi nợ tường minh)

`X-User-Id` hiện được wallet tin **mà chưa verify chữ ký HMAC** — vì wallet chưa có `InternalAuthFilter` (đó là Stage 4 của thiết kế wallet gốc, nợ đã ghi). Trong SP3, biên tin cậy là *mạng nội bộ + gateway là cửa duy nhất*. **Nợ kỹ thuật giữ nguyên và nhắc lại:** khi làm wallet Stage 4 (HMAC verify), header này mới thật sự đáng tin theo chuẩn zero-trust.

---

## 4. Luồng withdraw mới & ranh giới transaction

```
POST /wallets/7/withdraw  (X-User-Id: user-A, Idempotency-Key: k1, amount 50)
   │
   ▼ [0] Idempotency check (Stage 2, giữ nguyên): key đã có -> trả bút toán cũ, DỪNG
   ▼ [1] Scoped load: ví 7 CÓ thuộc user-A?  không -> 404 + audit log  (D2, D3)
   ▼ [2] ═══ CỔNG KYC (NGOÀI transaction!) ═══                          (D4)
   │     KycGate.check(user-A):
   │       cache có APPROVED? -> qua cổng (nhanh, không network)
   │       không -> gọi GET /kyc/cases/user-A/status (qua circuit breaker, timeout 2s)
   │            APPROVED      -> ghi cache (TTL) -> qua cổng
   │            khác          -> 403 KycNotApprovedException            (D7)
   │            breaker mở /
   │            timeout/5xx   -> 503 KycUnavailableException + Retry-After (D7, fail-closed)
   ▼ [3] ═══ TRANSACTION DB (ngắn, không network) ═══
   │     load lại ví (scoped) -> wallet.withdraw(50) -> save (+@Version)
   │     ghi WalletTransaction (ledger)
   ▼ [4] commit -> trả TransactionResponse
```

### ⚠️ Bẫy kỹ thuật phải né: Spring self-invocation

`WalletService.withdraw()` hiện là `@Transactional` trùm cả method. Tách "check KYC (ngoài)" và "chuyển tiền (trong)" mà gọi method `@Transactional` **từ trong cùng class** thì proxy Spring **không kích hoạt** transaction (self-invocation bypass). Hai cách đúng:

1. **`TransactionTemplate`** (đề xuất — tường minh, dễ hiểu): method ngoài không annotation, phần DB bọc trong `transactionTemplate.execute(...)`.
2. Tách bean: `WalletService` (điều phối, gọi KycGate) → `WalletLedgerService` (`@Transactional`, thuần DB).

> Bài học: ranh giới transaction là một QUYẾT ĐỊNH THIẾT KẾ, không phải chỗ tiện đâu dán annotation đó.

---

## 5. Cổng KYC — Port & Adapter (Clean Architecture)

```
wallet-service
├── domain/
│   ├── KycGate.java                  · PORT: KycDecision check(String userId)
│   │     enum KycDecision { ALLOWED, DENIED, UNAVAILABLE }
│   ├── KycNotApprovedException.java  · -> 403
│   └── KycUnavailableException.java  · -> 503 + Retry-After
├── application/WalletService.java    · withdraw: gate.check() TRƯỚC transaction
└── infrastructure/
    ├── kyc/
    │   ├── RestKycGate.java          · ADAPTER: RestClient + Resilience4j breaker + cache
    │   │     (gửi kèm X-Service-Id: wallet-service + HMAC nội bộ — kyc-service ĐÃ verify, Task SP2)
    │   └── KycStatusCache.java       · Caffeine: userId -> APPROVED, TTL
    └── messaging/
        └── KycRevokedConsumer.java   · @KafkaListener: xoá cache + compensation scan
```

- Domain chỉ biết `KycGate` — không biết REST/breaker/cache/Kafka (toàn bộ là chi tiết adapter).
- **Lưu ý hợp đồng:** kyc-service yêu cầu HMAC nội bộ (`X-Service-Id` thuộc allowlist — `wallet-service` ĐÃ có sẵn trong `kyc.allowed-services` từ SP2). Wallet adapter phải ký đúng canonical chung: `serviceId\nmethod\npath\ntimestamp\nsha256(body)`. Đây là lần thứ 3 canonical này được dùng — căng thêm lý do tách thư viện `shared-hmac` (nợ đã ghi, chưa làm).

### 5.1 Cache (Caffeine) — chính sách theo D6

| Mục | Giá trị đề xuất | Lý do |
|---|---|---|
| Thư viện | **Caffeine** (in-memory, in-process) | Đơn giản nhất cho học; không thêm hạ tầng. Redis là bước nâng cấp sau (đã nằm trong thiết kế wallet gốc). |
| Key → Value | `userId` → `APPROVED` (chỉ entry dương) | D6 |
| TTL | **60 giây** | Đủ ngắn để giới hạn cửa sổ stale-APPROVED khi event revoke trục trặc; đủ dài để hấp thụ burst rút tiền. Con số để duyệt/đổi. |
| Max size | 10.000 entries | Chặn memory leak; LRU evict. |
| Ghi cache | CHỈ khi KYC trả APPROVED | trạng thái khác không ghi (D6) |
| Xoá cache | (a) TTL hết · (b) nhận event `kyc.revoked` | cặp đôi D6+D9 |

### ⚠️ Bẫy multi-instance (ghi để biết, scope hiện tại 1 instance)

Caffeine là cache **per-instance**. Nếu chạy N instance wallet sau load-balancer mà cả N dùng **chung một consumer group**, Kafka chỉ giao event revoke cho **MỘT** instance → N-1 instance còn lại ôm cache APPROVED cũ tới hết TTL! Hai lối thoát khi scale: (a) mỗi instance một **consumer group id riêng** (`wallet-kyc-revoked-<random>`) để ai cũng nhận event (broadcast semantics); (b) chuyển cache sang **Redis dùng chung** — xoá một lần, mọi instance thấy. SP3 chạy 1 instance nên dùng group id cố định, nhưng **chọn sẵn phương án (a)** làm mặc định trong code (group id có suffix ngẫu nhiên) vì rẻ và đúng ngay khi scale.

### 5.2 Circuit Breaker (Resilience4j) — cấu hình đề xuất

| Tham số | Giá trị | Nghĩa |
|---|---|---|
| `slidingWindowSize` | 10 | Xét 10 lời gọi gần nhất |
| `failureRateThreshold` | 50% | ≥5/10 lỗi → MỞ mạch |
| `waitDurationInOpenState` | 10s | Mở 10s rồi sang HALF-OPEN thử lại |
| `permittedCallsInHalfOpenState` | 2 | 2 cuộc thăm dò |
| Timeout lời gọi KYC | **2s** (RestClient read-timeout) | Bài học gateway: không timeout = nhánh lỗi không bao giờ chạy |

Trạng thái breaker → hành vi cổng: CLOSED = gọi bình thường · OPEN = không gọi, fail-fast → cache hit thì ALLOWED, miss thì UNAVAILABLE (503) · HALF_OPEN = cho 2 cuộc thử.

**Fail-closed có cache-thoát-hiểm:** breaker mở KHÔNG có nghĩa chặn tất cả — user APPROVED còn cache vẫn rút được (thu nhỏ bán kính nổ — đúng kịch bản "KYC chết thì sao" đã phân tích).

---

## 6. Kafka — topic, schema, cấu hình

### 6.1 Hạ tầng
- **Chạy thật:** `docker-compose.yml` ở repo gốc — 1 broker Kafka (KRaft mode, không cần ZooKeeper), port 9092.
- **Test:** `spring-kafka-test` **EmbeddedKafka** — integration test không cần Docker.

### 6.2 Topic & event

| Mục | Giá trị | Lý do |
|---|---|---|
| Topic | `kyc.revoked` | Một topic một loại sự kiện — đơn giản cho học |
| Key | `userId` | Cùng user vào cùng partition → giữ THỨ TỰ theo user |
| Value (JSON) | `{"userId": "...", "reason": "...", "revokedAt": "ISO-8601"}` | `revokedAt` cần cho compensation scan (D5) |
| Partitions | 3 | Đủ minh hoạ partition; con số học tập |

### 6.3 Producer (kyc-service)
- Thay `LoggingKycEventPublisher` bằng `KafkaKycEventPublisher implements KycEventPublisher` — **chỉ thêm adapter mới, KHÔNG sửa domain/application** (port trả công đúng thiết kế SP2).
- `acks=all` (D8), `enable.idempotence=true` (chống trùng do producer retry).
- **Thứ tự trong transaction:** publish SAU khi DB commit thành công (publish trong `revoke()` sau `repository.save`; ghi nhận hạn chế: giữa commit và publish nếu crash thì event mất — *transactional outbox* là lời giải chuẩn, ghi vào YAGNI/nợ).

### 6.4 Consumer (wallet-service)
- `@KafkaListener(topics = "kyc.revoked", groupId = "wallet-kyc-revoked")`.
- Hành vi: parse event → `cache.evict(userId)` → compensation scan (mục 7). **Không dedup** (D9 — tự nhiên idempotent). Auto-commit offset (at-least-once là đủ vì idempotent).
- Lỗi parse/exception trong listener: log ERROR + bỏ qua message (không retry vô hạn chặn partition) — poison-pill đơn giản cho scope học; DLT (dead-letter topic) ghi vào YAGNI.

---

## 7. Compensation — quét ledger khi nhận revoke (D5)

Khi `KycRevokedConsumer` nhận event:

```
1. cache.evict(userId)                          ← chặn rút TIẾP THEO ngay lập tức
2. wallets = findByUserId(userId)
3. suspicious = ledger WHERE wallet_id IN (...) AND type = WITHDRAW AND created_at >= revokedAt
4. nếu có: log WARN  "COMPENSATION-ALERT userId={} revokedAt={} suspiciousWithdrawals={...}"
```

**Scope SP3: phát hiện + cảnh báo (log)** — đủ để học pattern detect & compensate và đủ để Ops hành động. Hành động tự động (đóng băng ví, bút toán đảo, báo cáo compliance) = YAGNI có chủ đích (mục 10).

---

## 8. Hợp đồng lỗi tổng hợp của withdraw (sau SP3)

| Tình huống | HTTP | Nguồn |
|---|---|---|
| Ví không tồn tại HOẶC không thuộc caller | 404 (+audit log nội bộ) | D3 |
| Thiếu `X-User-Id` | 400 | mới |
| KYC trả trạng thái ≠ APPROVED | **403** `{"error":"KYC approval required","status":"PENDING"}` | D7 |
| KYC không kiểm được (breaker mở/timeout/5xx) + cache miss | **503** + header `Retry-After: 10` | D7 |
| Không đủ tiền | 422 | Stage 2 |
| Thiếu Idempotency-Key / amount xấu | 400 | Stage 2 |
| Xung đột optimistic lock | 409 | Stage 2 |

(403 kèm `status` hiện tại để client hướng dẫn user đúng bước: PENDING → "đang chờ duyệt", NOT_STARTED → "hãy nộp hồ sơ".)

---

## 9. Ma trận tình huống (để viết test — tổng hợp toàn bộ hành vi cổng)

| KYC service | Cache | Trạng thái user | Kết quả withdraw |
|---|---|---|---|
| Sống | miss | APPROVED | ✅ rút + ghi cache |
| Sống | miss | PENDING/NOT_STARTED/REJECTED/REVOKED | 403 (không ghi cache) |
| Sống | hit APPROVED | (không gọi KYC) | ✅ rút, không network call |
| **Chết (breaker mở)** | hit APPROVED | — | ✅ rút (cache thoát hiểm) |
| **Chết (breaker mở)** | miss | — | 503 + Retry-After |
| Chậm > 2s | miss | — | timeout → tính như lỗi → 503 (và góp đà mở breaker) |
| Sau event revoke | (vừa bị evict) | REVOKED | gọi KYC thật → 403 + compensation log nếu có giao dịch sau revokedAt |

---

## 10. Nợ kỹ thuật & YAGNI

**Nợ ghi nhận (có chủ đích, có điều kiện tái xét):**
- `X-User-Id` chưa verify HMAC tại wallet → làm ở wallet Stage 4 (InternalAuthFilter như kyc-service đã có).
- Thư viện `shared-hmac` dùng chung canonical (giờ đã 3 nơi lặp lại).
- **Transactional outbox** cho producer (chống mất event giữa commit và publish).
- Redis thay Caffeine khi chạy nhiều instance (hoặc consumer-group-per-instance đã chọn sẵn).

**YAGNI (cố tình không làm ở SP3):**
- Hành động compensation tự động (freeze ví, reversal) — chỉ log alert.
- Dead-letter topic cho poison message.
- KYC tiers/hạn mức; gate cho topup (R1 nói rõ: nạp tự do).
- Backfill `user_id` cho dữ liệu cũ (H2 in-memory không có dữ liệu cũ).

---

## 11. Chiến lược kiểm thử

```
Unit (domain/application, fake KycGate):
  · withdraw bị chặn khi gate trả DENIED (403), UNAVAILABLE (503)
  · gate ALLOWED -> luồng Stage 2 nguyên vẹn (idempotency, ledger, balance)
  · scoped query: ví của user khác -> WalletNotFoundException
Adapter test (RestKycGate với MockWebServer):
  · KYC trả APPROVED -> ALLOWED + cache ghi
  · KYC trả PENDING -> DENIED + cache KHÔNG ghi
  · KYC 500/timeout liên tiếp -> breaker MỞ -> UNAVAILABLE ngay (không gọi nữa — đếm request MockWebServer)
  · breaker mở + cache có APPROVED -> ALLOWED (cache thoát hiểm)
Messaging test (EmbeddedKafka):
  · publish kyc.revoked -> consumer evict cache (user rút tiếp -> phải gọi KYC thật)
  · event trùng 2 lần -> không lỗi (naturally idempotent)
  · compensation: có withdraw sau revokedAt -> log WARN đúng nội dung
Integration (2 service thật + EmbeddedKafka, hoặc e2e script):
  · full flow: submit KYC -> webhook approve -> withdraw OK -> revoke -> withdraw bị 403
E2E thật (3 service + docker Kafka — như bài Content-Type đã dạy: mock dễ tính, chạy thật mới lòi):
  · kịch bản như integration nhưng qua gateway với JWT thật
```

---

## 12. Lộ trình triển khai đề xuất

1. **Định danh & sở hữu:** thêm `user_id` vào Wallet (domain+entity+mapper MapStruct), `X-User-Id` ở controller, scoped queries + 404 + audit log. (Tự đứng được, chưa cần KYC.)
2. **Port `KycGate` + luồng withdraw mới:** fake gate ALLOW-all để Stage 2 tests vẫn xanh; tách ranh giới transaction (TransactionTemplate).
3. **`RestKycGate`:** RestClient + HMAC ký + timeout 2s + Resilience4j breaker + Caffeine cache. Adapter tests với MockWebServer.
4. **Kafka producer (kyc-service):** docker-compose Kafka + `KafkaKycEventPublisher` + EmbeddedKafka test.
5. **Kafka consumer (wallet):** evict cache + compensation scan + tests.
6. **Integration + e2e thật** (3 service + Kafka): ma trận mục 9.
