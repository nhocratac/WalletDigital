# Thiết kế: SP5 — Multi-Tenant Isolation (schema-per-tenant thật)

- **Ngày:** 2026-06-15
- **Phạm vi:** Biến multi-tenant từ "thiết kế trên giấy" (doc 2026-06-09) thành **đồ thật**: cô lập dữ liệu **theo tenant ở tầng schema** trong `wallet-service` (và áp dụng được cho `kyc-service`). Thêm tenant routing, master registry, provisioning + Flyway, fleet migration. KHÔNG đổi nghiệp vụ ví/KYC; chỉ đổi *nơi dữ liệu sống* và *cách định tuyến*.
- **Tiền đề:** SP1–SP4 ✅ (ví + ledger + KYC gate + delayed settlement, 222 test). Gateway đã mang `tenantId` trong JWT và gửi `X-Tenant-Id` xuống (chưa ai dùng).
- **Mục tiêu học:** multi-tenancy strategies & tradeoffs, isolation by construction vs convention, tenant context propagation (ThreadLocal/routing datasource), database migration (Flyway), fleet migration + expand/contract (zero-downtime schema change), tương tác multi-tenant ↔ background worker (SP4).

> **Nguồn gốc:** các quyết định T1–T10 do **CHÍNH NGƯỜI HỌC suy ra** trong phiên Socratic (anh chỉ hỏi & phản biện). Mỗi quyết định kèm "lý do đã tự suy ra". Chi tiết cấu hình là đề xuất để duyệt. Diagram render ở file `.html` cùng tên.

---

## 1. Bối cảnh & Vấn đề

Nhu cầu gốc (từ buổi đầu): *"hệ thống multi-tenant ví — mỗi client một kho quản lý ví RIÊNG, nhưng vẫn chung 1 source code."*

Hiện trạng (verify trên code 2026-06-15):

```
JWT(tenantId) ─► [api-gateway] ✅ bóc tenantId, gửi X-Tenant-Id ─► [wallet-service] ❌ VỨT, không cô lập
                                                                          │
                                                                          ▼
                                                              1 schema chung — mọi tenant TRỘN chung
```

Gateway **mang** danh tính tenant tới tận cửa wallet (`AuthenticatedCaller(userId, tenantId)` → header `X-Tenant-Id`), nhưng wallet **vứt đi** — chỉ cô lập theo `userId` (D2/IDOR), **chưa** theo tenant. Tất cả tenant nằm chung một schema H2. (Đúng cùng hình lỗ hổng TraceId: gateway gắn, downstream bỏ.)

### Tenant là gì (làm rõ — khác user)

- **Tenant** = một **khách hàng-tổ chức** thuê nền tảng (vd Shopee, Lazada) — chạy chung 1 codebase, dữ liệu cách ly hoàn toàn.
- **User** = người dùng cuối *bên trong* một tenant.
- Một ví định danh bởi **cả hai**: *"ví của user An, thuộc tenant Shopee"*. `userId` chỉ duy nhất **trong phạm vi một tenant**.

| Tầng cô lập | Giữa | Cơ chế | Lộ thì |
|---|---|---|---|
| **Tenant** (SP5) | công ty ↔ công ty | **schema-per-tenant** | thảm họa hợp đồng/pháp lý |
| **User** (D2, đã có) | người ↔ người *trong 1 tenant* | `WHERE user_id = ?` | nội bộ, tệ nhưng nhỏ hơn |

---

## 2. Bảng quyết định (T1–T10 từ phiên Socratic)

| # | Quyết định | Lý do (đã tự suy ra) |
|---|---|---|
| T1 | Cô lập tenant bằng **schema-per-tenant**, KHÔNG phải "1 bảng chung + cột `tenant_id` + `WHERE tenant_id`". | Bảng chung cô lập bằng **quy ước** — phụ thuộc dev *nhớ* thêm `WHERE` ở mọi query; 1 lần quên (JOIN, report, dev mới) = lộ TOÀN BỘ tenant khác. Schema-per-tenant cô lập bằng **cấu trúc**: connection chỉ "nhìn thấy" schema một tenant → query trần lỡ quên filter vẫn vô hại. "Make illegal states unrepresentable." |
| T2 | Hậu quả lộ-chéo tenant biện minh cho chi phí nặng hơn so với cô lập tầng user. | Hai tenant là hai *công ty*, nhiều khi cạnh tranh → lộ chéo = vi phạm hợp đồng/pháp lý, mất doanh nghiệp (existential), khác hẳn user A thấy ví user B. |
| T3 | Routing: **`TenantFilter`** đọc `X-Tenant-Id` → đặt vào **`TenantContext` (ThreadLocal)** → **routing datasource** (Hibernate `CurrentTenantIdentifierResolver` / Spring `AbstractRoutingDataSource`) đọc context để chọn schema **lúc mở connection**. | Cùng pattern đã dùng 2 lần: `X-User-Id` và kế hoạch TraceId→MDC. Dời ranh giới tenant ra khỏi câu WHERE, xuống tầng connection. Gateway đã cấp sẵn `X-Tenant-Id`. |
| T4 | ThreadLocal **set ở đâu clear ở đó, trong `finally`** (đối xứng) — kể cả khi request ném exception. | Web server tái dùng thread từ pool; không clear → thread mang context tenant cũ sang phục vụ request tenant kế → **tái sinh đúng lỗ hổng lộ-chéo** (mà còn khó phát hiện: test 1 request luôn đúng, chỉ vỡ khi tải cao). Áp y hệt cho MDC traceId. |
| T5 | Có **master/registry schema** (không thuộc tenant nào) giữ danh bạ tenant (`tenantId → schema`, status...). schema-per-tenant ≠ MỌI thứ per-tenant. | Chicken-egg: routing phải tra map `tenantId→schema` *trước khi* vào bất kỳ tenant nào → map không thể nằm trong tenant schema. Worker cần danh sách tenant để lặp. Provisioning phải ghi tenant mới *trước khi* schema của nó tồn tại. |
| T6 | Provisioning **eager — tạo schema lúc ĐĂNG KÝ tenant** (onboarding), KHÔNG lazy lúc request đầu. | Lazy → user thật xui xẻo gửi request đầu phải chờ dựng cả schema (chậm + rủi ro/đua trong hot path). Onboarding = quy trình riêng: ghi registry + `CREATE SCHEMA` + Flyway migrate → user luôn gặp schema sẵn sàng. |
| T7 | **Flyway** đảm bảo mọi schema cùng cấu trúc: versioned SQL (`V1__`, `V2__`…) + bảng `flyway_schema_history` mỗi schema. | "Git cho cấu trúc DB": một bộ file V duy nhất là nguồn sự thật. Schema mới chạy V1..Vn từ đầu; schema cũ chỉ chạy V còn thiếu → tất cả **hội tụ** cùng hình dạng, mỗi V chạy đúng một lần. |
| T8 | Fleet migration (chạy V mới lên N schema): **per-schema độc lập, idempotent, cô lập lỗi, leo thang cho người** (như SP4 worker) + **expand/contract** để mixed-version an toàn. | Không thể migrate N schema nguyên tử (như "khe hở" phân tán SP3). Per-schema Flyway → lỗi tenant #37 không kéo 49 tenant kia; chạy lại bỏ qua cái đã xong, retry cái lỗi; lỗi dai → gắn cờ ops (như `NEEDS_MANUAL_REVIEW`). Expand/contract: migration chỉ-thêm, tương thích ngược → tenant V4 & V5 cùng chạy với code đang deploy. |
| T9 | **SP4 reconciliation worker** chạy trên thread `@Scheduled` (không có request, không có filter) → phải **lặp tenant registry + tự `TenantContext.set(tenant)` cho từng tenant** (rồi clear), không nhận tenant từ header. | Orders mỗi tenant ở schema khác nhau → không thể `SELECT ... FROM withdrawal_order` một phát. Reviewer SP4 đã chạm bản nhẹ ("isolate scheduler context from shared H2", commit `29751a9`). |
| T10 | **H2 → MySQL + Flyway** trở thành **bắt buộc** (không còn là nợ tùy chọn). Ghi nhận rủi ro **connection pool per-tenant**. | H2 in-memory khó mô phỏng schema-per-tenant + Flyway fleet một cách thực tế; cần một DB thật có `CREATE SCHEMA`/`CREATE DATABASE` rõ ràng + connection routing. Pool: nhiều tenant × pool riêng = bùng nổ kết nối → cần chiến lược pool (mục 8). |

---

## 3. Kiến trúc tổng (sau SP5)

```
                    JWT(userId, tenantId)
                          │
                    [api-gateway]  verify JWT, X-User-Id + X-Tenant-Id (đã ký HMAC)
                          │
                    [wallet-service]
                          │  TenantFilter: X-Tenant-Id → TenantContext (ThreadLocal)
                          │  (finally: clear)
                          ▼
                  Routing DataSource ──reads TenantContext──► chọn schema
                    │                │                │
            ┌───────┘        ┌───────┘        ┌───────┘
            ▼                ▼                ▼
     [schema: shopee]  [schema: lazada]  [schema: tiki]      ◄─ dữ liệu CỦA tenant
       wallet              wallet            wallet              (ví, ledger, withdrawal_order)
       wallet_transaction  ...               ...
       withdrawal_order

     [schema: master]   ◄─ dữ liệu VỀ tenant (không thuộc tenant nào)
       tenant_registry (tenant_id, schema_name, status, created_at)
```

> Hai tầng dữ liệu khác bản chất: **của** tenant (per-tenant schema) vs **về** tenant (master registry). User-level isolation (`WHERE user_id`, D2) vẫn áp **bên trong** mỗi schema tenant.

---

## 4. Tenant routing — Filter → Context → DataSource (T3, T4)

```
HTTP request (X-Tenant-Id: shopee)
   │
   ▼ TenantFilter (chạy SỚM, như TraceIdFilter)
   │     try { TenantContext.set("shopee"); chain.doFilter(...); }
   │     finally { TenantContext.clear(); }          ◄─ T4: bắt buộc, kể cả exception
   ▼ ... controller → service → repository ...
   ▼ khi mở connection:
        RoutingDataSource.determineCurrentLookupKey() → TenantContext.get() → "shopee"
        → connection trỏ vào schema shopee
```

- **`TenantContext`**: `ThreadLocal<String>` với `set/get/clear`. (Cùng cơ chế MDC.)
- **`TenantFilter`**: `OncePerRequestFilter`, đọc header `X-Tenant-Id`. Thiếu/blank → **400** (request hợp lệ phải qua gateway, gateway luôn gắn). KHÔNG đọc tenantId từ body/param (cùng triết lý D1: không tin client tự khai).
- **Routing datasource**: 2 lựa chọn (chốt khi viết plan):
  - **(a) Hibernate multi-tenancy SCHEMA** — `CurrentTenantIdentifierResolver` (đọc `TenantContext`) + `MultiTenantConnectionProvider` (đổi schema trên connection, vd `SET SCHEMA` / `USE`). *Khuyên dùng* — đúng chuẩn, tách bạch.
  - **(b) Spring `AbstractRoutingDataSource`** — map tenant→DataSource; đơn giản hơn nhưng dễ dẫn tới một pool/tenant (xem mục 8).

### Lỗ hổng tin cậy (ghi nợ, nhất quán SP3)
`X-Tenant-Id` (như `X-User-Id`) hiện wallet **tin mà chưa verify HMAC** — biên tin cậy là *mạng nội bộ + gateway là cửa duy nhất*. Wallet Stage 4 (InternalAuthFilter) sẽ verify; SP5 giữ nguyên giả định này.

---

## 5. Master registry & Provisioning (T5, T6)

### 5.1 Master schema
```
master.tenant_registry
  tenant_id     PK (vd "shopee")
  schema_name   (vd "tenant_shopee")
  status        ENUM(PROVISIONING, ACTIVE, MIGRATION_FAILED, SUSPENDED)
  created_at
```
Đọc được **trước khi** vào bất kỳ tenant nào (routing tra map; worker lấy danh sách; onboarding ghi tenant mới).

### 5.2 Onboarding workflow (eager, T6)
```
POST /admin/tenants {tenantId: "globex"}        (kênh admin, không phải user thường)
   1. INSERT tenant_registry(globex, tenant_globex, status=PROVISIONING)
   2. CREATE SCHEMA tenant_globex
   3. flyway.migrate(schema=tenant_globex)   → dựng V1..Vn (cấu trúc CHUẨN)
   4. UPDATE status = ACTIVE
   (lỗi giữa chừng → status=MIGRATION_FAILED, KHÔNG để ACTIVE nửa vời)
```
→ Khi user của globex gửi request đầu tiên, schema đã ACTIVE & đủ bảng. Request user **không bao giờ** phải dựng schema.

---

## 6. Flyway & Fleet Migration (T7, T8)

### 6.1 Migrations là nguồn sự thật
```
src/main/resources/db/migration/
  V1__create_wallet.sql
  V2__create_wallet_transaction.sql
  V3__add_user_id_to_wallet.sql
  V4__add_held_and_withdrawal_order.sql       ← cấu trúc SP4
  V5__...                                       ← thay đổi tương lai
```
Mỗi schema có `flyway_schema_history` riêng → `migrate` chỉ chạy V còn thiếu, đúng thứ tự, mỗi V một lần.

### 6.2 Chạy V mới lên N schema đang sống — **không nguyên tử được**
```
Migration job (pipeline deploy / lệnh ops):
  for tenant in tenant_registry where status = ACTIVE:
     try: flyway.migrate(tenant.schema)        # idempotent, per-schema độc lập
     except: mark status = MIGRATION_FAILED; log; CONTINUE (không chặn tenant khác)
  # chạy lại job → Flyway bỏ qua schema đã xong, chỉ retry cái FAILED
```
- Lỗi tenant #37 → 49 tenant kia vẫn xong; lỗi **cô lập** vào một tenant.
- Tenant lỗi dai → cờ `MIGRATION_FAILED` cho ops (như `NEEDS_MANUAL_REVIEW` SP4).

### 6.3 Expand/Contract — làm mixed-version AN TOÀN
**Không bao giờ ship migration mà code BẮT BUỘC có nó ngay.** Mọi thay đổi phá vỡ = chuỗi thay đổi chỉ-thêm, tương thích ngược:

| Pha | Việc | Bất biến |
|---|---|---|
| **Expand** | chỉ ADD (cột nullable / bảng mới) | code cũ chạy nguyên, code mới dùng nếu có |
| **Backfill** | điền dữ liệu dần, migrate hết N tenant | tenant V_cũ & V_mới cùng chạy với code deploy |
| **Contract** | sau khi MỌI tenant lên + code chỉ dùng đường mới → DROP cái cũ | xóa an toàn |

Ví dụ rename `owner_name → display_name` = 5 bước: add `display_name` → dual-write cả hai → backfill → switch read sang `display_name` → drop `owner_name`. KHÔNG `RENAME` một phát, KHÔNG `if(version)` rẽ nhánh runtime.

> Tách **deploy schema** và **deploy code**; migration luôn backward-compatible; chỉ xóa cũ khi chắc không ai dùng. (Cách đổi schema zero-downtime trên hệ đang chạy.)

---

## 7. Tương tác với SP4 — worker đa-tenant (T9)

Reconciliation worker (`@Scheduled`) chạy trên thread riêng, **không** có request/filter → phải tự lấy tenant + set context:

```
@Scheduled(...) reconcileAllTenants():
  for tenant in tenant_registry where status = ACTIVE:
     try {
        TenantContext.set(tenant.tenantId);
        reconcileService.runOnce();      # quét withdrawal_order trong schema tenant
     } finally {
        TenantContext.clear();           # T4 — bắt buộc
     }
```
- Webhook bank (fast path SP4) chạy trên **request thread** → có `X-Tenant-Id` → `TenantFilter` set context bình thường (bank phải gửi tenant trong callback, hoặc bankRef mã hóa tenant để tra registry).
- Reviewer SP4 đã chạm bản nhẹ của vấn đề này (`29751a9`); SP5 tổng quát hóa.

---

## 8. Connection pool & chọn DB (T10)

- **H2 → MySQL (hoặc Postgres) + Flyway:** SP5 cần DB thật có `CREATE SCHEMA`/`CREATE DATABASE` + đổi schema trên connection. H2 in-memory chỉ dùng cho test slice.
- **Rủi ro pool explosion:** mỗi tenant một `DataSource`/pool riêng → N tenant × pool size = quá nhiều kết nối. Chiến lược đề xuất (chốt khi viết plan):
  - **MỘT pool chung**, đổi schema trên connection mượn ra (`SET SCHEMA tenant_x` ở `MultiTenantConnectionProvider`, reset khi trả về pool) — *khuyên dùng*, không bùng nổ kết nối.
  - (Phương án per-tenant pool chỉ hợp khi số tenant nhỏ + cần cô lập tài nguyên mạnh.)
- **Noisy neighbor / data residency:** schema-per-tenant trong *một* DB chưa cô lập tài nguyên (CPU/IO) hay vị trí địa lý; nếu khách lớn yêu cầu → nâng lên **database-per-tenant** (cùng kiến trúc routing, chỉ đổi cấp cô lập). Ghi nhận, ngoài scope SP5.

---

## 9. Nợ kỹ thuật & YAGNI

**Nợ (có chủ đích):**
- `X-Tenant-Id` chưa verify HMAC tại wallet (wallet Stage 4).
- Áp cùng cơ chế tenant routing cho **kyc-service** (SP5 làm wallet trước; kyc tương tự).
- TraceId/MDC (task riêng đã thiết kế) — cùng họ ThreadLocal-cleanup với TenantContext, nên làm gần nhau.
- database-per-tenant cho khách cần cô lập tài nguyên/địa lý.

**YAGNI (không làm ở SP5):**
- UI admin onboarding (chỉ cần API `/admin/tenants`).
- Tự động scale pool theo số tenant; sharding tenant qua nhiều DB host.
- Per-tenant backup/restore, data residency theo vùng (chỉ ghi nhận schema-per-tenant *mở đường* cho chúng).
- Migrate dữ liệu hiện có sang multi-tenant (môi trường học, không có dữ liệu thật cũ).

---

## 10. Chiến lược kiểm thử

```
Unit:
  · TenantContext set/get/clear; TenantFilter set trước, clear trong finally (kể cả khi chain ném)
  · thiếu X-Tenant-Id -> 400
Routing (integration, MySQL/testcontainers hoặc 2 schema H2):
  · request tenant A -> chỉ thấy dữ liệu schema A; tenant B -> schema B
  · ⭐ query "trần" (thiếu WHERE) khi context=A -> KHÔNG trả dữ liệu B (chứng minh cô lập-bằng-cấu-trúc)
  · ⭐ thread tái dùng: request A rồi request B trên cùng thread -> B KHÔNG thấy A (chứng minh clear hoạt động)
Provisioning:
  · onboard tenant mới -> registry ACTIVE + schema tồn tại + đủ bảng (flyway tới version mới nhất)
  · migrate lỗi giữa chừng -> status=MIGRATION_FAILED, không ACTIVE
Fleet migration:
  · V mới chạy trên nhiều schema; 1 schema lỗi -> các schema khác vẫn lên; chạy lại retry đúng cái lỗi
  · expand/contract: tenant ở V_cũ và V_mới cùng phục vụ được với code hiện tại
SP4 worker:
  · worker lặp registry, set/clear context per tenant; reconcile đúng schema từng tenant
E2E thật:
  · 2 tenant (shopee, lazada) qua gateway với JWT khác tenantId -> ví hoàn toàn cách ly;
    tạo ví + topup ở shopee KHÔNG xuất hiện ở lazada
```

---

## 11. Lộ trình triển khai đề xuất (TDD)

1. **Hạ tầng DB:** H2→MySQL (testcontainers cho test), Flyway hóa schema hiện có (V1..V4 = trạng thái SP4) chạy trên schema mặc định trước.
2. **TenantContext + TenantFilter** (set/clear finally; 400 khi thiếu) — chưa routing, chỉ giữ context.
3. **Routing datasource** (Hibernate schema multi-tenancy, một pool + đổi schema) → test cô lập + test thread-reuse.
4. **Master registry + provisioning API** (`/admin/tenants`: insert + create schema + flyway migrate + status).
5. **Fleet migration job** (lặp registry, per-schema idempotent, cô lập lỗi, status MIGRATION_FAILED).
6. **SP4 worker đa-tenant** (lặp registry + set/clear context per tenant) + webhook lấy tenant từ bankRef/registry.
7. **(tùy chọn) Áp cho kyc-service.**
8. **Integration + e2e thật:** 2 tenant cách ly hoàn toàn qua gateway.
