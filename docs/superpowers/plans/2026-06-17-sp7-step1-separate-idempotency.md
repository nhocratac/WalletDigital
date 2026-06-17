# SP7 Bước 1 — Tách Idempotency Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development hoặc superpowers:executing-plans. Steps dùng checkbox (`- [ ]`).

**Goal:** Nhấc việc đảm bảo idempotency RA KHỎI sổ cái — tạo bảng `idempotency_record` (no-partition, UNIQUE(key) toàn cục, TTL purge) làm **lá chắn dedup duy nhất** cho mọi thao tác tiền (topup/withdraw/transfer), rồi **bỏ `UNIQUE(idempotency_key)` khỏi `wallet_transaction` + `withdrawal_order`**. Sau bước này ledger sạch constraint → SP7 Bước 2 (partition) khả thi.

**Architecture:** Theo design `docs/superpowers/specs/2026-06-17-sp7-ledger-at-scale-design.md` (L2). Dùng **expand/contract** (bài học SP5) để không vỡ hệ đang chạy: tạo bảng mới → dual-write → backfill → switch-read → drop constraint cũ. `idempotency_record` nằm **per-tenant-schema** (nhất quán SP5; key client là per-tenant), **không partition**. Reserve-key-FIRST (mô hình Stripe/Brandur): claim key qua UNIQUE *trước khi* chuyển tiền → race loser fail INSERT, không bao giờ chuyển tiền hai lần.

**Tech Stack:** Java 25, Spring Boot 3.4.4, JPA, Flyway (V5/V6 migration), H2 (slice) + Testcontainers MySQL (integration), MapStruct, Resilience4j.

**⚠️ LƯU Ý DRIFT:** Idempotency hiện ở **2 nơi**: `wallet_transaction.idempotency_key` (topup/transfer-OUT) và `withdrawal_order.idempotency_key` (withdraw), với race-recovery trong `executeWithIdempotentRecovery / executeWithdrawWithRecovery / executeTransferWithRecovery`. **Hành vi đối ngoại KHÔNG đổi** (replay trả cũ, same-key-diff-payload → 409, tiền bảo toàn) — chỉ *dời nơi enforce*. → toàn bộ 288 test cũ phải xanh; test mới chỉ thêm cho bảng record + chứng minh ledger hết UNIQUE.

---

## Quyết định khoá (chốt ở plan)

1. **Vị trí:** `idempotency_record` ở **mỗi tenant schema** (routed như `wallet_transaction`), **KHÔNG partition** (bảng nhỏ, purge được). *(Per-tenant vs shared là open question L8 — chọn per-tenant cho nhất quán SP5; alternative ghi nợ.)*
2. **Reserve-key-FIRST:** trong cùng transaction nghiệp vụ — INSERT `idempotency_record(key, fingerprint)` TRƯỚC; nếu trùng (DIVE) → recovery (đọc record: fingerprint khớp → replay; lệch → 409); nếu mới → chạy money op → cập nhật `result_ref`. Money chỉ chuyển SAU khi đã claim được key.
3. **Fingerprint** = hash ổn định của payload `(operationType, walletId|fromId+toId, amount)` — để phát hiện same-key-different-payload → 409 (thay `requireMatchingTransaction/Order`).
4. **`result_ref`** trỏ kết quả (txId / orderId / transferId) để replay trả đúng cái cũ.
5. **Expand/contract:** không bỏ UNIQUE cũ tới khi `idempotency_record` đã là nguồn enforce (Task 5). Mỗi pha một deploy/commit tương thích ngược.

---

## Cấu trúc thay đổi

```
wallet-service/
├── src/main/resources/db/migration/tenant/
│   ├── V5__create_idempotency_record.sql     (Create: bảng record, UNIQUE(key))
│   └── V6__drop_ledger_idempotency_unique.sql (Contract: bỏ uk_wt_idempotency_key + unique trên order)
├── src/main/java/com/vng/wallet/
│   ├── idempotency/                            (Create — package mới)
│   │   ├── IdempotencyRecord.java              (domain: key, opType, fingerprint, resultRef, createdAt)
│   │   ├── IdempotencyStore.java               (PORT: find(key), save(record))
│   │   ├── IdempotencyService.java             (reserve-first + fingerprint + recovery)
│   │   └── IdempotencyPurgeWorker.java         (TTL purge, @Scheduled, fleet per-tenant)
│   ├── infrastructure/persistence/ (IdempotencyRecordEntity, JpaIdempotencyStore, mapper)
│   └── application/WalletService.java          (Modify: 3 đường dedup → IdempotencyService)
```

---

## Task 1: Bảng + domain + store `idempotency_record` (expand)

**Files:** `db/migration/tenant/V5__create_idempotency_record.sql`; `idempotency/{IdempotencyRecord, IdempotencyStore}`; `persistence/{IdempotencyRecordEntity, JpaIdempotencyStore, SpringDataIdempotencyJpa}`; (test) `JpaIdempotencyStoreTest`.

- [ ] **Step 1: Migration V5** (portable H2+MySQL):
  ```sql
  CREATE TABLE idempotency_record (
      idempotency_key   VARCHAR(255) PRIMARY KEY,   -- UNIQUE toàn cục (trong schema tenant)
      operation_type    VARCHAR(32)  NOT NULL,       -- TOPUP / WITHDRAW / TRANSFER
      request_fingerprint VARCHAR(64) NOT NULL,       -- hash payload
      result_ref        VARCHAR(64),                  -- txId/orderId/transferId (null tới khi op xong)
      created_at        TIMESTAMP    NOT NULL         -- để TTL purge
  );
  ```
- [ ] **Step 2: Test** `JpaIdempotencyStoreTest` (Testcontainers MySQL / H2): save + find(key); INSERT trùng key → `DataIntegrityViolationException`; (multi-tenant) context A không thấy record của tenant B (routing SP5).
- [ ] **Step 3:** `IdempotencyRecord` (record), `IdempotencyStore` PORT (`Optional<IdempotencyRecord> find(String key)`, `IdempotencyRecord save(IdempotencyRecord r)`, `void updateResultRef(String key, String ref)`), entity + adapter + mapper. Flyway V5 nằm location `tenant`.
- [ ] **Step 4:** `cd wallet-service && mvn -q test` → 288 cũ + mới đều xanh (V5 chỉ THÊM bảng, không đụng gì → an toàn).
- [ ] **Step 5:** `git commit -m "feat(wallet): idempotency_record table + store (SP7 expand, L2)"`

---

## Task 2: `IdempotencyService` — reserve-first + fingerprint + recovery

**Files:** `idempotency/IdempotencyService.java`; (test) `IdempotencyServiceTest`.

- [ ] **Step 1: Test (ma trận, fake store):**
  - key mới → `reserve(key, opType, fingerprint)` thành công (record IN, resultRef null) → cho phép chạy op.
  - key đã có, fingerprint **khớp** → trả tín hiệu **replay** (kèm resultRef cũ), KHÔNG chạy op.
  - key đã có, fingerprint **lệch** → ném `IdempotencyKeyConflictException` (→ 409).
  - race: hai luồng cùng key → DIVE ở INSERT → recovery: đọc record → khớp replay / lệch 409 / record chưa có (winner rollback) → rethrow → 409. (tái dùng pattern winner/loser Stage 2.)
  - `fingerprintOf(opType, ...)` ổn định & phân biệt payload khác nhau.
- [ ] **Step 2:** `mvn -q test -Dtest=IdempotencyServiceTest` → FAIL.
- [ ] **Step 3:** Cài `IdempotencyService`:
  ```
  <T> Result reserveOrReplay(key, opType, fingerprint):
     try { store.save(new IdempotencyRecord(key, opType, fingerprint, null, now)); return FRESH; }
     catch (DataIntegrityViolationException e) {
        var rec = store.find(key).orElseThrow(() -> e);   // winner rollback -> 409
        if (!rec.fingerprint().equals(fingerprint)) throw new IdempotencyKeyConflictException(key);
        return REPLAY(rec.resultRef());
     }
  void complete(key, resultRef): store.updateResultRef(key, resultRef);
  ```
  (Gọi TRONG transaction nghiệp vụ — reserve trước, complete sau khi op xong.)
- [ ] **Step 4:** `mvn -q test` → PASS.
- [ ] **Step 5:** `git commit -m "feat(wallet): IdempotencyService reserve-first + fingerprint + race recovery"`

---

## Task 3: Đấu TOPUP + TRANSFER qua `idempotency_record` (dual-write + switch-read)

> Chuyển dedup của topup & transfer sang `IdempotencyService`. **Vẫn ghi** `idempotency_key` inline lên ledger (dual-write, chưa bỏ UNIQUE) để rollback an toàn — bỏ ở Task 5.

**Files:** Modify `application/WalletService.java` (topup path, transfer path); (test) `WalletServiceTest` cập nhật.

- [ ] **Step 1: Test:** topup/transfer replay & 409 vẫn đúng **nhưng giờ enforce qua `idempotency_record`** (verify record được tạo; ledger vẫn ghi như cũ). Tiền bảo toàn không đổi.
- [ ] **Step 2:** `mvn -q test -Dtest=WalletServiceTest` → FAIL (sau khi đổi check).
- [ ] **Step 3:** Trong `executeWithIdempotentRecovery` (topup) + `executeTransferWithRecovery`: thay "pre-check `findTransactionByIdempotencyKey`" bằng `idempotencyService.reserveOrReplay(...)`; FRESH → chạy money op → `complete(key, resultRef)`; REPLAY → trả kết quả qua resultRef; CONFLICT → 409. Giữ ghi `idempotency_key` inline (dual-write).
- [ ] **Step 4:** `mvn -q test` → PASS (toàn bộ).
- [ ] **Step 5:** `git commit -m "feat(wallet): route topup+transfer dedup through idempotency_record (dual-write)"`

---

## Task 4: Đấu WITHDRAW qua `idempotency_record`

**Files:** Modify `application/WalletService.java` (withdraw/`executeWithdrawWithRecovery`); (test) `WalletServiceTest`.

- [ ] **Step 1: Test:** withdraw replay/409 enforce qua `idempotency_record`; order vẫn tạo đúng; escrow/settle không đổi.
- [ ] **Step 2:** `mvn -q test` → FAIL → cài (reserveOrReplay trước khi tạo order; complete với orderId) → PASS.
- [ ] **Step 3:** `git commit -m "feat(wallet): route withdraw dedup through idempotency_record (dual-write)"`

---

## Task 5: Backfill + Contract — bỏ UNIQUE khỏi ledger

> Giờ `idempotency_record` là nguồn enforce. Backfill key cũ, rồi **bỏ `UNIQUE(idempotency_key)` khỏi `wallet_transaction` + `withdrawal_order`** → ledger sạch constraint.

**Files:** `db/migration/tenant/V6__drop_ledger_idempotency_unique.sql`; backfill script/service (per-tenant); Modify `WalletService` (ngừng dual-write inline key — hoặc giữ cột làm metadata, bỏ UNIQUE); (test) integration `IdempotencyContractIntegrationTest`.

- [ ] **Step 1: Backfill** (one-time, per tenant schema): copy `(idempotency_key, ...)` từ `wallet_transaction` + `withdrawal_order` vào `idempotency_record` (bỏ qua null/đã có). Idempotent, chạy được lại.
- [ ] **Step 2: Migration V6:** `ALTER TABLE wallet_transaction DROP INDEX uk_wt_idempotency_key;` + bỏ unique trên `withdrawal_order.idempotency_key`. (Cột có thể GIỮ làm metadata/đối soát, chỉ bỏ ràng buộc UNIQUE.)
- [ ] **Step 3: Test (⭐ chứng minh):**
  - INSERT hai bút toán cùng `idempotency_key` thẳng vào `wallet_transaction` → **KHÔNG còn bị DB chặn** (UNIQUE đã bỏ) → xác nhận ledger sạch constraint.
  - NHƯNG qua `WalletService` → vẫn chống trùng (vì `idempotency_record` chặn): topup/withdraw/transfer cùng key → replay/409 đúng.
  - tiền vẫn bảo toàn; 288 hành vi cũ không đổi.
- [ ] **Step 4:** `mvn -q test` → PASS. *(chú ý: test cũ nào assert "DIVE từ uk_wt_idempotency_key" phải đổi sang assert qua idempotency_record — drift có chủ đích.)*
- [ ] **Step 5:** `git commit -m "feat(wallet): backfill + drop ledger UNIQUE(idempotency_key) — enforcement now in idempotency_record (SP7 contract)"`

---

## Task 6: Purge TTL — bảng record không phình (fleet per-tenant)

**Files:** `idempotency/IdempotencyPurgeWorker.java`; (test) `IdempotencyPurgeWorkerTest`.

- [ ] **Step 1: Test:** record cũ hơn TTL (vd 7 ngày) bị xóa; record mới giữ lại; chạy per tenant (lặp registry SP5 + set/clear TenantContext như reconciliation worker).
- [ ] **Step 2:** `mvn -q test` → FAIL → cài `@Scheduled` (bật bằng `@ConditionalOnProperty wallet.idempotency.purge.enabled`; TTL config `wallet.idempotency.ttl-days:7`; lặp tenant registry + `DELETE WHERE created_at < now - ttl`) → PASS.
- [ ] **Step 3:** `git commit -m "feat(wallet): idempotency_record TTL purge worker (per-tenant fleet)"`

---

## Nợ kỹ thuật & YAGNI
- **Per-tenant vs shared** idempotency store (L8) — chọn per-tenant; nếu cần dedup xuyên tenant thì revisit.
- Lock `IN_PROGRESS` kiểu Stripe (chống hai request đồng thời *đang xử lý*, không chỉ đã-xong) — hiện dựa reserve-first + DIVE là đủ; thêm status nếu cần.
- SP7 Bước 2 (partition ledger) — chỉ làm khi đo được; nay đã *mở khoá* được vì ledger hết UNIQUE.
- `X-User-Id`/`X-Tenant-Id` chưa verify HMAC (wallet Stage 4).

## Checklist Done
- [ ] `idempotency_record` (no-partition, UNIQUE key, per-tenant) là nguồn enforce dedup duy nhất.
- [ ] topup/withdraw/transfer: replay trả cũ, same-key-diff-payload → 409, reserve-first (race loser không chuyển tiền) — hành vi không đổi.
- [ ] `wallet_transaction` + `withdrawal_order` **hết** UNIQUE(idempotency_key) → ledger partitionable.
- [ ] purge TTL chạy per-tenant, bảng record không phình.
- [ ] 288 test cũ xanh + test mới; git clean.
