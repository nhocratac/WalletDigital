# Thiết kế: Multi-Tenant Wallet Service

- **Ngày:** 2026-06-09
- **Phạm vi:** MỘT microservice duy nhất — `wallet-service`
- **Mục tiêu:** Dự án học để rèn tư duy Software Architect (multi-tenancy, Clean Architecture, kiến trúc tài chính).

> Tài liệu này là "hợp đồng thiết kế" trước khi code. Mọi quyết định đều kèm *lý do* và *đánh đổi* — vì đó là bản chất công việc của một architect.

---

## 1. Bối cảnh & Vấn đề

Xây một service quản lý ví tiền phục vụ **nhiều khách hàng (tenant)** trên **cùng một codebase**, nhưng dữ liệu mỗi khách phải **tách biệt tuyệt đối** — khách A không bao giờ thấy/đụng được ví của khách B.

`wallet-service` là một mắt xích trong hệ microservice lớn hơn. Việc xác thực người dùng (JWT) do **một service khác** (API Gateway / Auth Service) đảm nhiệm — KHÔNG thuộc phạm vi service này (nguyên tắc *single responsibility*).

```
Người dùng --JWT--> [API Gateway/Auth Service] --X-Tenant-Id--> [wallet-service]  ◄── ta xây cái này
                     (ngoài phạm vi)                            (1 service)
```

---

## 2. Các quyết định kiến trúc & lý do

| Hạng mục | Lựa chọn | Lý do / Đánh đổi |
|---|---|---|
| Mức tách biệt dữ liệu | **Schema-per-tenant** (mỗi tenant 1 schema, chung 1 database) | Cân bằng giữa tách biệt và độ phức tạp. Mạnh hơn "chung bảng + tenant_id", nhẹ hơn "DB-per-tenant". |
| Xác định tenant | **HTTP header `X-Tenant-Id`** | Đơn giản, hợp pattern gọi nội bộ giữa các microservice (header đã đáng tin vì gateway xác thực trước). Tách riêng nên sau đổi sang JWT dễ. |
| Cấu trúc code | **Clean Architecture (thực dụng)** | Lõi nghiệp vụ không phụ thuộc DB; schema-routing gói gọn trong infrastructure. Đánh đổi: nhiều file hơn, có bước map domain↔entity. |
| Cơ chế routing schema | **Hibernate multi-tenancy (SCHEMA)** | Dùng cơ chế chuẩn công nghiệp (`CurrentTenantIdentifierResolver` + `MultiTenantConnectionProvider`), không tự chế. |
| Tạo schema | **Pre-provision từ config** | An toàn cho hệ tài chính: chỉ tenant khai báo trước mới hợp lệ; tenant lạ → từ chối (không tự đẻ schema rác). |
| Mô hình tiền tệ | **Ledger bất biến + balance cache** (cùng 1 transaction) | Chuẩn tài chính: lưu được lịch sử/đối soát, không sửa lén. Balance cache để đọc nhanh. |
| Đồng thời (concurrency) | **Optimistic locking (`@Version`)** | Chặn lost-update khi 2 request cùng sửa 1 ví. Rẻ, phù hợp tần suất ghi vừa phải. |
| Chống lặp (idempotency) | **`Idempotency-Key` (UNIQUE trên transaction)** | Retry mạng không làm cộng/trừ tiền hai lần. |
| Kiểu dữ liệu tiền | **`BigDecimal`**, không bao giờ `double`/`float` | Tránh sai số dấu phẩy động — bắt buộc với tiền. |
| Xác thực service-to-service | **HMAC ký request** (allowlist service nội bộ) | Wallet không xác thực *người dùng* (gateway lo), nhưng phải xác thực *service gọi tới mình* (zero-trust). HMAC ở tầng app, dạy chữ ký + chống sửa + chống replay. |
| Observability | **TraceId (Correlation ID)** qua Micrometer + MDC | Mỗi request một id, in vào mọi dòng log, truyền qua header giữa các service để nối log xuyên suốt. Rẻ, giá trị thật. |
| Resilience (Circuit Breaker) | **Hoãn** — chưa có outbound call để bảo vệ | Wallet hiện chỉ nói chuyện với DB của nó, không gọi service khác → circuit breaker chưa có gì để bọc. Thêm khi có remote call thật (tránh cargo-cult). |

---

## 3. Luồng request (Data Flow)

Điểm cốt lõi: **code nghiệp vụ KHÔNG biết gì về tenant**. Việc định tuyến schema xảy ra ngầm ở tầng hạ tầng.

Thứ tự các filter rất quan trọng — chặn sớm cái rẻ và cái bảo mật trước:

```
curl -H "X-Service-Id: api-gateway" -H "X-Signature: ..." -H "X-Timestamp: ..."
     -H "X-Tenant-Id: acme" -H "X-Trace-Id: t-789" -H "Idempotency-Key: abc-123"
     POST /wallets/1/topup {amount:50}
   │
   ▼ [1] InternalAuthFilter: "Service nào gọi tao?" → tra allowlist + verify HMAC + check timestamp
   │       Sai chữ ký → 401 · Không trong allowlist → 403 · Quá hạn → 401  (chặn sớm nhất)
   ▼ [2] TraceIdFilter: đọc X-Trace-Id (không có thì sinh mới) → nhét vào MDC để log
   ▼ [3] TenantFilter: đọc X-Tenant-Id → set vào TenantContext (ThreadLocal)
   │       try { ... } FINALLY { TenantContext.clear() }   ← bắt buộc, chống rò tenant qua thread tái dùng
   ▼ [4] Controller → Service → (Port) WalletRepository   ← code thuần nghiệp vụ, vô tư về tenant/auth/trace
   ▼ [5] Hibernate hỏi CurrentTenantResolver → "acme" → đổi connection sang schema "acme"
   ▼ [6] SQL chạy trong schema "acme". Tenant "beta" không thấy dữ liệu.
```

---

## 4. Cấu trúc code (Clean Architecture)

Quy tắc phụ thuộc: **mũi tên luôn trỏ vào trong** (infrastructure → application → domain).

```
com.vng.wallet
├── domain/                          ← LÕI: thuần Java, KHÔNG import Spring/JPA
│   ├── Wallet.java                  · model có hành vi: topup(), withdraw() (quy tắc nghiệp vụ ở đây)
│   ├── WalletTransaction.java       · một bút toán bất biến trong sổ cái
│   ├── WalletRepository.java        · PORT (interface thuần): save, findById, ...
│   ├── WalletNotFoundException.java
│   └── InsufficientFundsException.java
│
├── application/                     ← USE CASES: điều phối nghiệp vụ, quản lý @Transactional
│   └── WalletService.java           · createWallet, getWallet, topup, withdraw, listTransactions
│
└── infrastructure/                  ← chi tiết kỹ thuật, phụ thuộc vào trong
    ├── persistence/
    │   ├── WalletEntity.java            · @Entity JPA (có @Version) — TÁCH khỏi domain Wallet
    │   ├── WalletTransactionEntity.java · @Entity JPA (idempotency_key UNIQUE)
    │   ├── SpringDataWalletJpa.java     · extends JpaRepository
    │   └── JpaWalletRepository.java     · ADAPTER: implements domain WalletRepository, map Entity↔domain
    ├── security/                     ◄══ xác thực service-to-service (HMAC) ══
    │   ├── InternalAuthFilter.java      · verify HMAC + allowlist + chống replay (chạy ĐẦU TIÊN)
    │   ├── HmacVerifier.java            · dựng lại canonical, tính & so sánh chữ ký (constant-time)
    │   └── AllowedServicesConfig.java   · nạp allowlist {serviceId: secret} từ application.yml/env
    ├── observability/                ◄══ TraceId / Correlation ID ══
    │   └── TraceIdFilter.java           · đọc/sinh X-Trace-Id, nhét vào MDC để log
    ├── tenant/                       ◄══ toàn bộ cơ chế multi-tenant gói gọn ở đây ══
    │   ├── TenantContext.java           · ThreadLocal giữ tenant id hiện tại (+ clear())
    │   ├── TenantFilter.java            · đọc header X-Tenant-Id, validate, finally→clear
    │   ├── CurrentTenantResolver.java   · Hibernate CurrentTenantIdentifierResolver
    │   ├── SchemaConnectionProvider.java· Hibernate MultiTenantConnectionProvider (đổi schema)
    │   └── TenantSchemaInitializer.java · lúc khởi động: tạo schema + bảng cho mỗi tenant trong config
    └── web/
        ├── WalletController.java
        ├── dto/  (CreateWalletRequest, TopupRequest, WithdrawRequest, WalletResponse, TransactionResponse)
        └── GlobalExceptionHandler.java
```

**Nguyên tắc kiểm chứng:** gói `domain/` phải copy sang project khác vẫn biên dịch được (không dính Spring/JPA). Nếu không, ranh giới đã sai.

---

## 4b. Bảo mật service-to-service & Observability (cross-cutting)

### Hai câu hỏi xác thực KHÁC nhau (đừng gộp)

- **"USER này là ai?"** → API Gateway lo (verify JWT người dùng). KHÔNG thuộc wallet-service.
- **"SERVICE nào đang gọi tôi?"** → wallet-service **phải tự kiểm**. "Tin gateway" ≠ "tin bất kỳ ai gửi được packet". Đây là tinh thần *zero-trust*: không tin dựa trên vị trí mạng.

### HMAC service-to-service

Caller (gateway) gửi kèm mỗi request:

```
X-Service-Id:  api-gateway
X-Timestamp:   1749470000
X-Signature:   HMAC-SHA256(secret, canonical)
   canonical = serviceId + "\n" + method + "\n" + path + "\n" + timestamp + "\n" + sha256(body)
```

`InternalAuthFilter` verify theo thứ tự:
1. `X-Service-Id` có trong **allowlist** (config)? Không → `403 Forbidden`.
2. Lấy secret của service đó → dựng lại canonical → tính HMAC → so sánh **constant-time**. Sai → `401`.
3. `|now - X-Timestamp| < 5 phút`? Quá hạn → `401` (chống replay request cũ).

```yaml
# application.yml — allowlist; secret nạp từ biến môi trường, KHÔNG hardcode
wallet.internal-auth.allowed-services:
  api-gateway: "${GATEWAY_HMAC_SECRET}"
  batch-job:   "${BATCH_HMAC_SECRET}"
```

> **Bài học:** (1) Secret luôn nạp từ env/secret manager, không hardcode. (2) Dùng **allowlist** (mặc định từ chối) chứ không blocklist. (3) mTLS mạnh hơn nhưng thường thuộc tầng **hạ tầng/service mesh**, không phải code app — biết ranh giới này là tư duy architect.

### TraceId (Correlation ID)

`TraceIdFilter` đọc header `X-Trace-Id` (không có thì sinh mới), nhét vào **MDC** để mọi dòng log tự in trace id ra. Dùng **Micrometer Tracing** (có sẵn trong Spring Boot 3). Trace id được truyền tiếp qua header khi gọi service khác → nối log xuyên suốt cả hệ. Lõi nghiệp vụ không biết gì về nó.

## 5. Mô hình dữ liệu (trong MỖI schema tenant)

```
wallet                              wallet_transaction (sổ cái, append-only)
─────────────────────              ──────────────────────────────────────
id            PK                    id              PK
owner_name                          wallet_id       FK → wallet.id
balance       (cache đọc nhanh)     type            TOPUP | WITHDRAW
version       (@Version)            amount          BigDecimal (> 0)
                                    idempotency_key  UNIQUE
                                    balance_after   BigDecimal
                                    created_at       timestamp
```

`balance` trên `wallet` là bản cache; nguồn sự thật là tổng các bút toán. Mỗi thao tác cập nhật **cả hai bảng trong cùng một `@Transactional`** — cùng commit hoặc cùng rollback.

---

## 6. API & Xử lý lỗi

### Endpoints (mọi endpoint yêu cầu header `X-Tenant-Id`)

| Method | Path | Mô tả |
|---|---|---|
| POST | `/wallets` | Tạo ví mới (balance khởi tạo = 0) |
| GET | `/wallets/{id}` | Xem thông tin + số dư ví |
| POST | `/wallets/{id}/topup` | Nạp tiền (cần `Idempotency-Key`) |
| POST | `/wallets/{id}/withdraw` | Rút tiền (cần `Idempotency-Key`; chặn nếu không đủ) |
| GET | `/wallets/{id}/transactions` | Xem lịch sử bút toán |

### Bảng quyết định lỗi → HTTP code

| Tình huống | Exception | HTTP |
|---|---|---|
| Chữ ký HMAC sai / thiếu / quá hạn timestamp | `InvalidSignatureException` | 401 |
| `X-Service-Id` không có trong allowlist | `ServiceNotAllowedException` | 403 |
| Thiếu/sai/không hợp lệ `X-Tenant-Id` | `UnknownTenantException` | 400 |
| Ví không tồn tại | `WalletNotFoundException` | 404 |
| Rút quá số dư | `InsufficientFundsException` | 422 |
| Input sai (amount ≤ 0, ownerName rỗng) | validation | 400 |
| Xung đột version (optimistic lock) | `OptimisticLockException` | 409 |

Mọi lỗi trả về cùng một cấu trúc JSON nhất quán qua `GlobalExceptionHandler`.

---

## 7. Chiến lược kiểm thử (Testing)

```
Unit test (nhanh, không cần DB):
  · Domain Wallet: topup cộng đúng, withdraw chặn khi thiếu tiền, amount âm bị từ chối.
Service test (port giả / mock repository):
  · WalletService điều phối đúng, không đụng DB thật.
Integration test (DB thật):
  · ⭐ TENANT ISOLATION: tạo ví ở "acme" → GET bằng "beta" PHẢI trả 404.
  · IDEMPOTENCY: gọi topup 2 lần cùng Idempotency-Key → chỉ cộng 1 lần.
  · CONCURRENCY: 2 topup đồng thời → không lost update (nhờ @Version).
```

Test quan trọng nhất là **tenant isolation** — nó là cam kết "tiền khách A không lẫn sang khách B".

---

## 8. Nợ kỹ thuật & những gì CỐ TÌNH bỏ qua (YAGNI)

**Nợ kỹ thuật đã ghi nhận:**
- `spring.jpa.hibernate.ddl-auto=update` chỉ dùng cho giai đoạn học. Production cần **Flyway/Liquibase** với migration chạy cho *từng schema tenant*.

**Cố tình chưa làm (đủ cho mục tiêu học, tránh over-engineering):**
- Đa tiền tệ (mỗi ví hiện coi như một loại tiền).
- Phân trang/lọc cho lịch sử giao dịch.
- Auto-provision schema cho tenant lạ (đã chọn pre-provision an toàn hơn).
- Xác thực JWT *người dùng* (thuộc gateway; wallet chỉ xác thực *service* qua HMAC).
- **Circuit Breaker**: hoãn vì wallet chưa có outbound call nào để bảo vệ. Thêm khi service thật sự gọi ra ngoài (vd notification-service), dùng Resilience4j.
- Chuyển tiền giữa hai ví (transfer) — có thể là tính năng học tiếp theo.

---

## 9. Lộ trình triển khai đề xuất (chia nhỏ để học từng khái niệm)

1. **Refactor sang Clean Architecture** — tách domain/application/infrastructure từ code Stage 1.
2. **Ledger + topup/withdraw** — thêm transaction, optimistic locking, idempotency (chưa multi-tenant).
3. **Multi-tenant** — TenantContext, TenantFilter (+finally clear), Hibernate schema routing, pre-provision.
4. **Security HMAC + TraceId** — InternalAuthFilter (allowlist + chữ ký + chống replay), TraceIdFilter (MDC). Đúng thứ tự filter.
5. **Integration test** — chứng minh tenant isolation, idempotency, concurrency, và chặn request không có chữ ký hợp lệ.
6. (Tùy chọn) Chuyển H2 → MySQL qua Docker; bàn về Flyway.
