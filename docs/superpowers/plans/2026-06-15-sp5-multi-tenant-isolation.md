# SP5 — Multi-Tenant Isolation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Cô lập dữ liệu **theo tenant ở tầng schema** trong `wallet-service`: `X-Tenant-Id` (gateway đã gửi) → `TenantContext` (ThreadLocal) → routing datasource chọn schema lúc mở connection. Thêm **master registry**, **provisioning + Flyway**, **fleet migration**, và sửa **SP4 reconciliation worker** để chạy đa-tenant.

**Architecture:** Theo design `docs/superpowers/specs/2026-06-15-sp5-multi-tenant-isolation-design.md` (T1–T10). Hibernate multi-tenancy **SCHEMA** strategy: `CurrentTenantIdentifierResolver` (đọc `TenantContext`) + `MultiTenantConnectionProvider` (một pool, `SET SCHEMA` lúc mượn / reset lúc trả). Hai persistence concern: **master** (single, không-routed — giữ `tenant_registry`) vs **tenant** (routed — wallet/ledger/order). Flyway thay `ddl-auto`.

**Tech Stack:** Java 25, Spring Boot 3.4.4, Spring Data JPA, **Flyway** (mới), **MySQL** (prod target) + **Testcontainers MySQL** (integration), **H2** (vẫn dùng cho slice test nhanh — H2 *có* hỗ trợ `CREATE SCHEMA`/`SET SCHEMA` nên cơ chế schema-per-tenant test được trên H2; Testcontainers MySQL chứng minh prod-realism), MapStruct, Resilience4j.

**⚠️ LƯU Ý DRIFT:** wallet đã qua SP1–SP4 (222 test). SP5 đụng **hạ tầng persistence** — rủi ro làm vỡ test cũ cao nhất ở Task 1 (bỏ `ddl-auto`, chuyển Flyway) và Task 4 (routing datasource). Nguyên tắc: **mỗi task giữ toàn bộ test cũ xanh**; chỉ thêm/đổi nơi nêu rõ. Code block = trạng thái ĐÍCH của phần nêu, không phải toàn file.

**⚠️ FAIL-CLOSED (an toàn tiền tệ):** khi `TenantContext` rỗng (chưa set), routing **KHÔNG được** im lặng trỏ vào một schema mặc định có dữ liệu thật — phải ném lỗi hoặc trỏ schema rỗng. "Quên set tenant" phải *ồn ào fail*, không *âm thầm lộ chéo* (T1, T4).

---

## Quyết định khoá (chốt ở plan)

1. **Strategy:** Hibernate `MultiTenancySettings` = `SCHEMA`. Một `DataSource`/pool chung; `MultiTenantConnectionProvider.getConnection(tenant)` gọi `conn.setSchema(schemaOf(tenant))` (H2/MySQL: `SET SCHEMA`/`USE`), `releaseConnection` reset về schema trung lập trước khi trả pool (T4 ở tầng connection).
2. **Tên schema:** tenant `acme` → schema `tenant_acme`; master → schema `master`. Map giữ trong `tenant_registry`.
3. **Hai persistence unit:**
   - **master EMF/DataSource** (KHÔNG routed) — chỉ entity `TenantRegistry`, schema cố định `master`. Đọc được trước khi vào tenant nào (giải chicken-egg T5).
   - **tenant EMF** (routed) — wallet/ledger/withdrawal_order, đi qua connection provider.
4. **Flyway 2 location:** `db/migration/master` (tạo `tenant_registry`) và `db/migration/tenant` (wallet/ledger/order = trạng thái SP4). `ddl-auto=none`.
5. **Trust:** `X-Tenant-Id` chưa verify HMAC (wallet Stage 4 — nợ giữ nguyên).
6. **DB test:** unit/slice → H2 (multi-schema); routing & isolation & provisioning & fleet → Testcontainers MySQL (realism). SQL migration viết portable (H2 + MySQL).

---

## Cấu trúc thay đổi

```
wallet-service/
├── pom.xml  (+ flyway-core, flyway-mysql, mysql-connector-j, testcontainers:mysql(test))
├── src/main/resources/
│   ├── application.properties  (ddl-auto=none; flyway; datasource MySQL prod, H2 dev/test)
│   └── db/migration/
│        ├── master/  V1__create_tenant_registry.sql
│        └── tenant/  V1__create_wallet.sql ... V4__add_held_and_withdrawal_order.sql  (= schema SP4)
├── src/main/java/com/vng/wallet/
│   ├── tenancy/                                  (Create — package mới)
│   │   ├── TenantContext.java                    (ThreadLocal<String> set/get/clear)
│   │   ├── TenantFilter.java                     (đọc X-Tenant-Id → set; finally clear; thiếu→400)
│   │   ├── TenantSchemaResolver.java             (CurrentTenantIdentifierResolver, đọc TenantContext; rỗng→fail-closed)
│   │   ├── SchemaMultiTenantConnectionProvider.java (1 pool + SET SCHEMA + reset)
│   │   └── TenantProvisioningService.java        (CREATE SCHEMA + flyway.migrate)
│   ├── tenancy/master/                           (master persistence unit — non-routed)
│   │   ├── TenantRegistry.java (entity) · TenantRegistryRepository · MasterDataSourceConfig
│   ├── infrastructure/
│   │   ├── scheduling/ReconciliationWorker.java  (Modify: lặp registry + set/clear context per tenant)
│   │   └── web/ (AdminTenantController: POST /admin/tenants; WithdrawalWebhookController: tenant từ bankRef)
└── (config) HibernateMultiTenancyConfig.java     (đăng ký resolver + connection provider)
```

---

## Task 1: Flyway-hóa schema hiện tại (bỏ `ddl-auto`) — single-schema vẫn xanh

> Mục tiêu: thay Hibernate auto-DDL bằng Flyway migrations **mà không** đổi hành vi. Chạy trên schema mặc định trước; chưa có tenant routing. Mọi test SP1–SP4 phải xanh.

**Files:** `pom.xml`, `application.properties`, `db/migration/tenant/V1..V4`, (test) một `FlywaySchemaIntegrationTest`.

- [ ] **Step 1:** Thêm deps: `flyway-core` (+ `flyway-mysql` cho MySQL), `mysql-connector-j`, `org.testcontainers:mysql` (test scope). Giữ `h2` (test).
- [ ] **Step 2: Viết migrations `db/migration/tenant/`** tái tạo ĐÚNG schema SP4 (portable H2+MySQL):
  - `V1__create_wallet.sql` — `wallet(id, user_id NOT NULL, owner_name, balance numeric(38,2) default 0, held numeric(38,2) default 0, version)` *(gộp held ngay nếu muốn ít file; hoặc tách V3/V4 theo lịch sử — chọn gộp cho gọn, miễn cấu trúc đích đúng)*.
  - `V2__create_wallet_transaction.sql` — kèm UNIQUE(idempotency_key).
  - `V3__create_withdrawal_order.sql` — kèm UNIQUE(idempotency_key), UNIQUE(bank_ref), cột attempt_count/first_sent_at/state/version.
  - (Đối chiếu cột với `WalletEntity/WalletTransactionEntity/WithdrawalOrderEntity` — không lệch.)
- [ ] **Step 3:** `application.properties`: `spring.jpa.hibernate.ddl-auto=none`; bật Flyway (`spring.flyway.locations=classpath:db/migration/tenant` tạm thời cho single-schema); H2 cho test, MySQL cho prod (profile/env).
- [ ] **Step 4: Test** `FlywaySchemaIntegrationTest` (Testcontainers MySQL): app khởi động, Flyway chạy V1..V3, `flyway_schema_history` tới version mới nhất, một topup+withdraw chạy được.
- [ ] **Step 5:** `mvn -q test` → **toàn bộ 136 test wallet xanh** (slice test trên H2 cũng phải chạy Flyway hoặc cấu hình tương thích). Sửa cấu hình test nếu cần (vd `@DataJpaTest` + Flyway).
- [ ] **Step 6:** `git commit -m "feat(wallet): Flyway migrations replace ddl-auto (single-schema baseline) + MySQL/Testcontainers"`

---

## Task 2: `TenantContext` + `TenantFilter` (T3, T4) — giữ context, chưa routing

**Files:** `tenancy/TenantContext.java`, `tenancy/TenantFilter.java`; (test) `TenantContextTest`, `TenantFilterTest`.

- [ ] **Step 1: Test:**
  - `TenantContext.set("acme")` → `get()`=="acme"; `clear()` → `get()`==null.
  - `TenantFilter`: request có `X-Tenant-Id: acme` → trong chain `TenantContext.get()`=="acme"; **sau** chain (finally) == null.
  - chain ném exception → context **vẫn** được clear (finally).
  - thiếu/blank `X-Tenant-Id` → **400**, KHÔNG vào chain.
  - **thread-reuse:** gọi filter cho acme xong, gọi lại (không header set lại) → không rò context cũ (clear đã chạy).
- [ ] **Step 2:** `mvn -q test -Dtest=TenantContextTest,TenantFilterTest` → FAIL.
- [ ] **Step 3:** Cài `TenantContext` (ThreadLocal); `TenantFilter extends OncePerRequestFilter`, đăng ký chạy SỚM (như `TraceIdFilter` của gateway). `try { set; chain } finally { clear }`. KHÔNG đọc tenant từ body/param (D1 triết lý).
- [ ] **Step 4:** `mvn -q test` → PASS.
- [ ] **Step 5:** `git commit -m "feat(wallet): TenantContext (ThreadLocal) + TenantFilter, clear-in-finally, 400 on missing (T3,T4)"`

---

## Task 3: Master persistence unit + `tenant_registry` (T5)

**Files:** `tenancy/master/{TenantRegistry, TenantRegistryRepository, MasterDataSourceConfig}`, `db/migration/master/V1__create_tenant_registry.sql`; (test) `TenantRegistryRepositoryTest`.

- [ ] **Step 1: Test** (Testcontainers MySQL hoặc H2 schema `master`): lưu `TenantRegistry(tenantId, schemaName, status=PROVISIONING)`; `findByStatus(ACTIVE)`; `tenant_id` PK/unique. Đọc được **không** cần TenantContext (non-routed).
- [ ] **Step 2:** `mvn -q test -Dtest=TenantRegistryRepositoryTest` → FAIL.
- [ ] **Step 3:** `TenantRegistry` entity (`tenant_id`, `schema_name`, `status` enum `{PROVISIONING, ACTIVE, MIGRATION_FAILED, SUSPENDED}`, `created_at`) trong schema `master`. `MasterDataSourceConfig`: EMF/transactionManager RIÊNG trỏ schema `master`, **không** đi qua tenant connection provider (đây là điểm tách 2 persistence unit). Flyway master location chạy migration tạo bảng.
- [ ] **Step 4:** `mvn -q test` → PASS.
- [ ] **Step 5:** `git commit -m "feat(wallet): master persistence unit + tenant_registry (non-routed, T5)"`

---

## Task 4: ⭐ Routing datasource — Hibernate SCHEMA multi-tenancy (T1, T3)

> Trái tim SP5. Sau task này, request mang `X-Tenant-Id` chỉ thấy schema của tenant đó.

**Files:** `tenancy/{TenantSchemaResolver, SchemaMultiTenantConnectionProvider}`, `HibernateMultiTenancyConfig`; (test) `TenantIsolationIntegrationTest` (Testcontainers MySQL, **2 schema tenant**).

- [ ] **Step 1: Test (⭐ chứng minh cô lập-bằng-cấu-trúc):**
  - provision sẵn 2 schema `tenant_a`, `tenant_b` (migrate tenant location).
  - context=a → tạo ví → context=b → list ví: **rỗng** (không thấy ví của a).
  - ⭐ **query "trần" thiếu WHERE** (vd `repository.findAll()` / count) khi context=a → CHỈ trả dữ liệu a (chứng minh quên-WHERE-vẫn-an-toàn).
  - ⭐ **thread-reuse:** cùng một thread phục vụ a rồi b → b KHÔNG thấy a.
  - **fail-closed:** context rỗng → thao tác DB ném lỗi (KHÔNG trỏ schema có dữ liệu).
- [ ] **Step 2:** `mvn -q test -Dtest=TenantIsolationIntegrationTest` → FAIL.
- [ ] **Step 3:** Cài:
  - `TenantSchemaResolver implements CurrentTenantIdentifierResolver` → `resolveCurrentTenantIdentifier()` = `TenantContext.get()`; rỗng → trả sentinel KHÔNG map tới dữ liệu (fail-closed) hoặc ném.
  - `SchemaMultiTenantConnectionProvider implements MultiTenantConnectionProvider` → `getConnection(tenant)`: mượn từ pool chung, `SET SCHEMA tenant_<id>`; `releaseConnection`: reset schema trung lập rồi trả pool.
  - `HibernateMultiTenancyConfig`: đăng ký 2 bean trên vào tenant EMF (`hibernate.multiTenancy=SCHEMA`).
- [ ] **Step 4:** `mvn -q test` → PASS (gồm toàn bộ test cũ — chú ý test cũ chạy với một tenant mặc định/seed schema).
- [ ] **Step 5:** `git commit -m "feat(wallet): schema-per-tenant routing (Hibernate SCHEMA multitenancy), isolation-by-construction + fail-closed (T1,T3)"`

---

## Task 5: Provisioning — onboarding tenant mới (T6, T7)

**Files:** `tenancy/TenantProvisioningService`, `web/AdminTenantController`; (test) `TenantProvisioningServiceTest`, `AdminTenantControllerTest`.

- [ ] **Step 1: Test:**
  - `provision("globex")`: registry có `globex/tenant_globex/PROVISIONING` → `CREATE SCHEMA tenant_globex` → `flyway.migrate(tenant_globex)` (V1..Vn) → status `ACTIVE`; schema có đủ bảng (wallet/order/...).
  - flyway lỗi giữa chừng → status `MIGRATION_FAILED`, KHÔNG `ACTIVE`.
  - sau provision: set context=globex → tạo ví chạy được.
  - `POST /admin/tenants {tenantId}` → 201; gọi lại cùng tenant → 409 (đã tồn tại).
- [ ] **Step 2:** `mvn -q test` → FAIL → cài → PASS.
- [ ] **Step 3:** `TenantProvisioningService`: dùng một `Flyway` cấu hình `schemas(name).locations("db/migration/tenant")` rồi `.migrate()` cho từng schema mới. `AdminTenantController` kênh admin (AuthZ: header role `ops`/HMAC như kyc — scope plan: kiểm role).
- [ ] **Step 4:** `git commit -m "feat(wallet): tenant provisioning (CREATE SCHEMA + flyway migrate) + /admin/tenants (T6,T7)"`

---

## Task 6: Fleet migration job (T8)

**Files:** `tenancy/FleetMigrationService` (+ trigger: lệnh/endpoint admin); (test) `FleetMigrationServiceTest`.

- [ ] **Step 1: Test:**
  - 3 tenant ACTIVE ở version cũ; chạy fleet migrate (thêm `V_next`) → cả 3 lên version mới.
  - tenant #2 lỗi (vd SQL không hợp với dữ liệu nó) → #1, #3 vẫn lên; #2 → `MIGRATION_FAILED`; job KHÔNG dừng.
  - chạy lại job → Flyway bỏ qua #1/#3 (đã xong), retry #2.
  - (ghi chú test expand/contract: migration `V_next` chỉ ADD cột nullable → ví cũ vẫn đọc/ghi được trong lúc mixed-version.)
- [ ] **Step 2:** `mvn -q test` → FAIL → cài → PASS.
- [ ] **Step 3:** `FleetMigrationService.migrateAll()`: đọc `tenant_registry` ACTIVE, `for` từng schema `flyway.migrate`, try/catch per-tenant → set `MIGRATION_FAILED` + log, CONTINUE. Idempotent (Flyway history). Trigger qua `/admin/tenants/migrate` hoặc lệnh.
- [ ] **Step 4:** `git commit -m "feat(wallet): fleet migration job — per-schema idempotent, isolate failures (T8)"`

---

## Task 7: SP4 reconciliation worker đa-tenant (T9)

> Worker `@Scheduled` chạy trên thread KHÔNG có request/filter → phải tự lặp registry + set/clear context per tenant. Đây là chỗ SP5 sửa SP4.

**Files:** Modify `infrastructure/scheduling/ReconciliationWorker.java`, `web/WithdrawalWebhookController.java`; (test) bổ sung `ReconciliationWorkerMultiTenantTest`.

- [ ] **Step 1: Test:**
  - 2 tenant, mỗi tenant có order `PENDING`/`SENT`; worker chạy một vòng → lặp registry, set context từng tenant, reconcile orders **đúng schema từng tenant** (order tenant a không bị xử lý dưới context b).
  - sau mỗi tenant, context được clear (T4).
  - webhook bank: xác định tenant từ `bankRef` (bankRef nên mã hóa/tra registry → tenant) rồi set context trước khi `applyTerminal`. Test webhook cho order ở schema đúng.
- [ ] **Step 2:** `mvn -q test` → FAIL.
- [ ] **Step 3:** Sửa worker:
  ```
  for tenant in tenantRegistry.findByStatus(ACTIVE):
     try { TenantContext.set(tenant.id); reconcileService.runOnce(); }
     finally { TenantContext.clear(); }
  ```
  Webhook: parse tenant từ `bankRef` (hoặc payload) → `TenantContext.set` → `applyTerminal` → clear (filter không tự biết tenant nếu bank không gửi `X-Tenant-Id`; chốt: `bankRef` định dạng `<tenant>-<orderId>-<rand>` hoặc tra registry).
- [ ] **Step 4:** `mvn -q test` → PASS (gồm test SP4 cũ — chú ý chúng giờ chạy trong một tenant context seed).
- [ ] **Step 5:** `git commit -m "feat(wallet): multi-tenant reconciliation worker + webhook tenant resolution (T9)"`

---

## Task 8: Integration + E2E thật — 2 tenant cách ly

**Files:** Modify `e2e/` (lib.sh: JWT cho 2 tenant; scenario-tenant.sh), README.

- [ ] **Step 1: Integration** (`@SpringBootTest` + Testcontainers MySQL): onboard `acme` + `globex`; qua API tạo ví ở acme (context qua TenantFilter) → list ví globex rỗng; topup acme không ảnh hưởng globex. Worker reconcile cả hai độc lập.
- [ ] **Step 2: e2e thật:** `lib.sh` sinh 2 JWT (`tenantId=acme`, `tenantId=globex`). `scenario-tenant.sh`: onboard 2 tenant (`/admin/tenants`) → tạo ví + topup ở acme → dùng JWT globex list/truy cập ví của acme → **404** (không thấy); tạo ví globex độc lập. (Chạy với MySQL thật qua docker-compose hoặc Testcontainers.)
- [ ] **Step 3:** Chạy 3 service + MySQL + Kafka, xác nhận cách ly. Dọn (kill PID giữ cổng).
- [ ] **Step 4:** `git commit -m "test(sp5): integration + real e2e — two tenants fully isolated"`

---

## Nợ kỹ thuật & YAGNI (nhắc từ design §9)
- `X-Tenant-Id` chưa verify HMAC (wallet Stage 4).
- Áp cùng cơ chế cho **kyc-service** (SP5 làm wallet trước).
- TraceId/MDC task (cùng họ ThreadLocal-cleanup — nên làm gần).
- database-per-tenant (cô lập tài nguyên/địa lý); connection pool tuning theo số tenant.
- UI admin onboarding; data residency theo vùng.

## Checklist Done
- [ ] Flyway thay ddl-auto; mọi schema (cũ/mới) hội tụ cùng cấu trúc.
- [ ] request tenant A chỉ thấy schema A; query trần thiếu WHERE vẫn cô lập; context rỗng → fail-closed.
- [ ] thread-reuse không rò context (clear-in-finally).
- [ ] onboard tenant mới: CREATE SCHEMA + flyway migrate + ACTIVE; lỗi → MIGRATION_FAILED.
- [ ] fleet migrate: per-schema idempotent, cô lập lỗi, resumable.
- [ ] reconciliation worker lặp tenant + set/clear context; webhook resolve tenant.
- [ ] e2e thật: 2 tenant cách ly hoàn toàn qua gateway.
- [ ] toàn bộ test xanh (wallet + gateway + kyc); git tree clean.
