# SP6 — Internal Transfer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Thêm chuyển tiền **ví → ví** (`POST /wallets/{fromId}/transfer`) vào `wallet-service`: một transaction ACID double-entry, cổng KYC bên gửi, scoped sender / by-id receiver, intra-tenant, một idempotency-key, chặn self-transfer.

**Architecture:** Theo design `docs/superpowers/specs/2026-06-16-sp6-internal-transfer-design.md` (TR1–TR8). Tái dùng: `Wallet.withdraw()`/`topup()` (debit/credit balance — transfer tức thời, KHÔNG escrow), `executeWithIdempotentRecovery` + `@Version` race-recovery (Stage 2), `KycGate` (SP3), `TransactionTemplate` (gọi KYC NGOÀI tx — D4), scoped query `findByIdAndUserId` (D2), `TenantContext` routing (SP5 — chặn cross-tenant *miễn phí*).

**Tech Stack:** Java 25, Spring Boot 3.4.4, JPA, **Flyway** (thêm cột qua migration — SP5 đã bỏ ddl-auto), H2 (slice) + Testcontainers MySQL (integration), Resilience4j, MapStruct.

**⚠️ LƯU Ý DRIFT:** wallet đã qua SP1–SP5 (262 test). `Wallet.Type` hiện = `{TOPUP, WITHDRAW_HOLD, WITHDRAW_SETTLED, WITHDRAW_REFUNDED}`; withdraw giờ order-based (escrow). Transfer **tức thời** nên dùng `Wallet.withdraw(amount)` (trừ balance trực tiếp, có check thiếu tiền) + `Wallet.topup(amount)` (cộng) — KHÔNG dùng reserve/settle. Mọi test cũ phải xanh. Code block = trạng thái ĐÍCH của phần nêu.

---

## Quyết định khoá (chốt ở plan)

1. **Tức thời, không escrow:** transfer-out = `from.withdraw(amount)` (balance−=, ném InsufficientFunds nếu thiếu); transfer-in = `to.topup(amount)` (balance+=). Cả hai trong **một** `txTemplate.execute`.
2. **Double-entry + transferId:** ghi 2 `WalletTransaction`: `TRANSFER_OUT`(from) + `TRANSFER_IN`(to), cùng `transferId` (nhóm cặp). Thêm cột `transfer_id` (nullable) qua **Flyway migration mới** (tenant location).
3. **Idempotency-key đặt trên CHÂN OUT:** `idempotency_key = K` trên `TRANSFER_OUT`; chân `TRANSFER_IN` có `idempotency_key = NULL` (nó chỉ tồn tại kèm OUT, không replay độc lập). → cột `idempotency_key` phải **nullable** (migration; MySQL UNIQUE cho phép nhiều NULL). Replay: `findTransactionByIdempotencyKey(K)` → thấy OUT → trả transfer cũ. Same-key-different-payload → 409 (tái dùng `requireMatchingTransaction`).
4. **Lock-ordering theo `wallet_id`:** load/khóa hai ví theo id tăng dần (id nhỏ trước) — phòng deadlock nếu sau này dùng pessimistic, và để optimistic dễ suy luận. Mặc định **optimistic @Version + retry giới hạn** (TR2).
5. **Receiver load KHÔNG scoped theo caller:** thêm `WalletRepository.findById(Long)` (chỉ trong tenant hiện tại nhờ routing SP5). Sender vẫn `findByIdAndUserId` (D2).
6. **KYC bên gửi NGOÀI tx** (D4): `kycGate.check(caller)` trước khi mở transaction; DENIED→403, UNAVAILABLE→503. Receiver KHÔNG check KYC (TR4).
7. **Self-transfer** (`fromId == toWalletId`) → 400, validate sớm (TR6).

---

## Cấu trúc thay đổi

```
wallet-service/
├── src/main/resources/db/migration/tenant/
│   └── V4__add_transfer_columns.sql      (Create: transfer_id; idempotency_key -> nullable)
├── src/main/java/com/vng/wallet/
│   ├── domain/
│   │   ├── WalletTransaction.java         (Modify: Type += TRANSFER_OUT, TRANSFER_IN; + transferId field)
│   │   └── WalletRepository.java          (Modify: + findById(Long))
│   ├── application/WalletService.java     (Modify: + transfer(...))
│   └── infrastructure/
│       ├── persistence/ (WalletTransactionEntity + transfer_id; JpaWalletRepository.findById; mapper)
│       └── web/ (WalletController + POST /{id}/transfer; dto/TransferRequest, TransferResponse; GlobalExceptionHandler)
```

---

## Task 1: Domain & ledger — Type mới + transferId + Flyway migration

**Files:** Modify `domain/WalletTransaction.java`; Create `db/migration/tenant/V4__add_transfer_columns.sql`; Modify `persistence/WalletTransactionEntity.java`; (test) `domain/WalletTransactionTest` (nếu có) / cập nhật test tham chiếu Type.

- [ ] **Step 1: Migration `V4__add_transfer_columns.sql`** (portable H2+MySQL):
  ```sql
  ALTER TABLE wallet_transaction ADD COLUMN transfer_id VARCHAR(64);
  ALTER TABLE wallet_transaction MODIFY idempotency_key VARCHAR(255) NULL; -- chân IN không có key
  ```
  *(Cú pháp MODIFY/ALTER có thể khác giữa H2/MySQL — kiểm và viết cho khớp cả hai; nếu cần tách 2 file/biến thể thì ghi rõ.)*
- [ ] **Step 2: `WalletTransaction.Type`** → `{ TOPUP, WITHDRAW_HOLD, WITHDRAW_SETTLED, WITHDRAW_REFUNDED, TRANSFER_OUT, TRANSFER_IN }`. Thêm field `transferId` (String, nullable) vào record + `WalletTransactionEntity` (`@Column(name="transfer_id")`) + mapper.
- [ ] **Step 3:** Test: tạo `WalletTransaction` loại TRANSFER_OUT/IN có transferId; persist+load qua `JpaWalletRepositoryTest` (Testcontainers MySQL hoặc H2) — cột mới lưu đúng, `idempotency_key` null được cho chân IN.
- [ ] **Step 4:** `cd wallet-service && mvn -q test` → toàn bộ xanh (sửa dây chuyền compile do thêm field record).
- [ ] **Step 5:** `git commit -m "feat(wallet): ledger TRANSFER_OUT/IN + transfer_id (Flyway V4), idempotency_key nullable"`

---

## Task 2: Repository — `findById` cho ví NHẬN (không scoped, TR5)

**Files:** Modify `domain/WalletRepository.java`, `persistence/JpaWalletRepository.java`, `SpringDataWalletJpa.java`; (test) `JpaWalletRepositoryTest`.

- [ ] **Step 1: Test:** `findById(id)` trả ví bất kể user_id (ví nhận không thuộc caller); trong môi trường multi-tenant (SP5) chỉ thấy ví trong schema tenant hiện tại (routing tự lọc) — test cô lập: context A `findById(viB_của_tenantB)` → rỗng.
- [ ] **Step 2:** `mvn -q test -Dtest=JpaWalletRepositoryTest` → FAIL.
- [ ] **Step 3:** Thêm `Optional<Wallet> findById(Long id)` vào port + adapter (Spring Data có sẵn `findById`; map entity→domain). Giữ `findByIdAndUserId` cho sender.
- [ ] **Step 4:** `mvn -q test` → PASS.
- [ ] **Step 5:** `git commit -m "feat(wallet): WalletRepository.findById for non-scoped receiver lookup (TR5)"`

---

## Task 3: Use case `transfer` (TR1–TR7) — trái tim SP6

**Files:** Modify `application/WalletService.java`; (test) `application/WalletServiceTest` (+ fake KycGate).

- [ ] **Step 1: Test (ma trận):**
  - `transfer(from, to, caller, amount, K)` thành công: from.balance−=amount, to.balance+=amount; tổng không đổi; 2 bút toán TRANSFER_OUT(from,key=K) + TRANSFER_IN(to,key=null) cùng transferId.
  - self-transfer (from==to) → IllegalArgument/400.
  - from không đủ tiền → InsufficientFunds (422), KHÔNG ghi bút toán nào (rollback).
  - KYC sender DENIED → KycNotApproved (403), không đụng tiền; UNAVAILABLE → 503.
  - replay cùng K → trả transfer cũ, KHÔNG chuyển lần hai (balance không đổi lần 2).
  - cùng K khác payload (to/amount khác) → IdempotencyKeyConflict (409).
  - sender không thuộc caller → WalletNotFound (404).
  - receiver không tồn tại → lỗi "recipient not found" (404/422).
- [ ] **Step 2:** `mvn -q test -Dtest=WalletServiceTest` → FAIL.
- [ ] **Step 3: Cài `transfer`:**
  ```
  public TransferResult transfer(Long fromId, Long toId, String caller, BigDecimal amount, String key):
    validate: amount>0, key not blank, fromId != toId            // TR6
    replay: findTransactionByIdempotencyKey(key) present -> requireMatching(OUT, from, TRANSFER_OUT, amount) -> trả cũ  // TR7
    kycGate.check(caller): DENIED->403, UNAVAILABLE->503          // TR4, NGOÀI tx (D4)
    txTemplate.execute:
        // lock-ordering: nạp theo wallet_id tăng dần            // TR2
        from = findByIdAndUserId(fromId, caller) or 404          // TR5/D2
        to   = findById(toId) or 404 "recipient not found"       // TR5
        from.withdraw(amount)  // balance-=, InsufficientFunds nếu thiếu
        to.topup(amount)
        save(from); save(to)                                     // @Version cả hai
        transferId = UUID
        saveTransaction(TRANSFER_OUT, from, amount, key,  transferId)
        saveTransaction(TRANSFER_IN,  to,  amount, null, transferId)
    bọc DIVE/OptimisticLock -> retry giới hạn -> 409             // tái dùng executeWithIdempotentRecovery pattern
  ```
  (Tổng quát hoá `executeWithIdempotentRecovery` để dùng cho transfer, hoặc viết biến thể `executeTransferWithRecovery` cùng cơ chế.)
- [ ] **Step 4:** `mvn -q test` → PASS.
- [ ] **Step 5:** `git commit -m "feat(wallet): internal transfer use case — ACID double-entry, sender-KYC, scoped+idempotent (TR1-7)"`

---

## Task 4: Controller + DTO + hợp đồng lỗi

**Files:** Modify `web/WalletController.java`, `web/GlobalExceptionHandler.java`; Create `web/dto/TransferRequest.java`, `TransferResponse.java`; (test) `WalletControllerTest`.

- [ ] **Step 1: Test:** `POST /wallets/{fromId}/transfer` body `{toWalletId, amount}` header `X-User-Id`, `Idempotency-Key` → 200 + TransferResponse{transferId, from, to, amount}; self-transfer 400; sender sai chủ 404; KYC denied 403; thiếu tiền 422; thiếu Idempotency-Key 400.
- [ ] **Step 2:** `mvn -q test -Dtest=WalletControllerTest` → FAIL.
- [ ] **Step 3:** Controller endpoint (đọc `X-User-Id`, `Idempotency-Key`; `toWalletId`+`amount` từ body — KHÔNG đọc fromId/caller từ body). DTO. `GlobalExceptionHandler` map: self-transfer/blank→400, WalletNotFound→404, KycNotApproved→403, KycUnavailable→503+Retry-After, InsufficientFunds→422, OptimisticLock→409, IdempotencyKeyConflict→409. (Gateway đã forward `Idempotency-Key` từ bug-fix e2e trước.)
- [ ] **Step 4:** `mvn -q test` → PASS.
- [ ] **Step 5:** `git commit -m "feat(wallet): POST /wallets/{id}/transfer endpoint + error contract"`

---

## Task 5: Concurrency + multi-tenant + integration/e2e

**Files:** (test) `WalletTransferConcurrencyIntegrationTest`, `WalletTransferTenantIsolationIntegrationTest` (Testcontainers MySQL); Modify `e2e/` (scenario transfer).

- [ ] **Step 1: Concurrency test:** hai transfer đồng thời đụng cùng ví (vd A→B và C→B) → cả hai không làm mất/nhân tiền; nếu đụng version, một retry/409; tổng balance cuối đúng (bảo toàn). (CompletableFuture/2 thread.)
- [ ] **Step 2: Multi-tenant test (TR3):** ví nhận "cùng id nhưng khác tenant" → transfer trả 404 (routing SP5 chặn — ví nhận không tồn tại trong schema caller).
- [ ] **Step 3: Integration/e2e:** A topup 100 → transfer 30 cho B → A.balance=70, B.balance+=30, tổng bảo toàn; A chưa KYC → 403. (Thêm bước vào e2e `scenario.sh` hoặc scenario riêng; chú ý Idempotency-Key toàn cục như các SP trước.)
- [ ] **Step 4:** `mvn -q test` (full, 3 module) → xanh.
- [ ] **Step 5:** `git commit -m "test(sp6): transfer concurrency + tenant isolation + e2e (money conserved)"`

---

## Nợ kỹ thuật & YAGNI
- `X-User-Id`/`X-Tenant-Id` chưa verify HMAC (wallet Stage 4).
- Velocity/transaction limit khi transfer → SP riêng (chống gian lận).
- Notification cho người nhận; transfer "nhận/từ chối" (push model, credit ngay).
- Pessimistic + lock-ordering: chỉ thêm khi *đo được* ví nóng.

## Checklist Done
- [ ] transfer: 1 tx, double-entry TRANSFER_OUT/IN cùng transferId; tổng bảo toàn.
- [ ] sender-KYC gác (403/503) NGOÀI tx; receiver không cần KYC.
- [ ] sender scoped D2 (404); receiver by-id; self-transfer 400; cross-tenant 404.
- [ ] 1 idempotency-key trên chân OUT; replay trả cũ; same-key-diff-payload 409.
- [ ] concurrency: không mất/nhân tiền; optimistic retry→409.
- [ ] toàn bộ test xanh (wallet+gateway+kyc); git clean.
