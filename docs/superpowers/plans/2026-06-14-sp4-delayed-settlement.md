# SP4 — Delayed Settlement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Biến `withdraw` từ thao tác tức thời thành **vòng đời bất đồng bộ**: tạo `WithdrawalOrder` (state machine `PENDING→SENT→SETTLED/FAILED/NEEDS_MANUAL_REVIEW`), giữ tiền trong **escrow** (available/held), gọi ngân hàng (mock) NGOÀI transaction, và một **reconciliation worker** lái mọi lệnh tới đích — self-healing sau crash, idempotent với ngân hàng để không trả kép.

**Architecture:** Theo design `docs/superpowers/specs/2026-06-14-sp4-delayed-settlement-design.md` (quyết định E1–E10, do người học tự suy ra). Port mới `BankClient` trong domain wallet; adapter `RestBankClient` (RestClient + HMAC + Resilience4j) + `MockBankClient` (test/dev) trong infrastructure. Cú gọi bank NGOÀI transaction (tái dùng `TransactionTemplate` đã có). `WithdrawalOrder` là aggregate giữ state + transition guard (như `KycCase` của SP2). Reconciliation worker là `@Scheduled` đọc các order chưa-terminal từ DB (DB chính là hàng đợi việc-cần-làm bền vững).

**Tech Stack:** Java 25, Spring Boot 3.4.4, Spring Data JPA, H2, Resilience4j 2.2.0 (đã có từ SP3), MapStruct, MockWebServer (test), Awaitility (test), spring-kafka (đã có).

**⚠️ LƯU Ý DRIFT:** wallet-service đã qua nhiều vòng review-fix (blank-key validation, race recovery `IdempotencyKeyConflictException`, scoped query D2/D3, cổng KYC D4 ngoài tx, KycRevokedConsumer). Khi sửa file CÓ SẴN: áp đúng *delta* mô tả và **GIỮ NGUYÊN hành vi đã có**. Code block cho file sửa = trạng thái ĐÍCH của phần được nêu, không phải toàn file. SP4 **thay thế** đường withdraw tức thời cũ bằng đường order-based — các test cũ giả định `withdraw` trả `WalletTransaction` ngay + trừ `balance` ngay sẽ phải cập nhật (đây là drift có chủ đích, ghi rõ ở Task 3).

---

## Mô hình & quyết định khoá (chốt ở plan)

**Số dư (E2, E3):** `Wallet` giữ thêm field `held`.
- `balance` = **total** (tổng còn sở hữu, gồm cả tiền đang chờ rút) — field cũ, giữ nguyên ngữ nghĩa.
- `held` = phần đang giữ cho order `PENDING`/`SENT`.
- `available = balance − held` ← lệnh rút MỚI soi số này (đủ `available` mới cho rút).
- **① hold:** `held += amount` (balance không đổi — tiền vẫn sở hữu, chỉ bị giữ).
- **③ settle:** `balance −= amount; held −= amount` (tiền thật rời hệ; available không đổi vì đã rời available lúc hold — đúng E3).
- **③ refund:** `held −= amount` (available phục hồi, balance không đổi).

> Bất biến (system invariant, để test §6 thiết kế): với mỗi ví, `available = balance − held ≥ 0` ở MỌI thời điểm; mỗi transition là một cặp thay đổi nhất quán.

**Ledger (E4):** `WalletTransaction.Type` thêm `WITHDRAW_HOLD, WITHDRAW_SETTLED, WITHDRAW_REFUNDED` (giữ `TOPUP`; bỏ `WITHDRAW` trần — thay bằng 3 sự kiện vòng đời). Mỗi transition append 1 bút toán bất biến với `balanceAfter` = total sau bước đó. Ledger vẫn là sổ cái append-only của Stage 2.

**Idempotency 2 tầng (E7):**
- Tầng user→wallet: `Idempotency-Key` map 1-1 tới `WithdrawalOrder` (cột `idempotency_key UNIQUE`). Replay → trả order cũ.
- Tầng wallet→bank: `bank_ref` (`UNIQUE`, **sinh ở bước ① trong cùng transaction tạo order**). Mọi lần gọi/ query bank — kể cả worker sau crash — dùng LẠI `bank_ref` này.

**Concurrency worker:** `WithdrawalOrder` có `@Version` (optimistic lock). Worker đọc batch order chưa-terminal; cập nhật dưới `@Version` → hai worker đụng nhau thì một thua `OptimisticLockException` và bỏ qua (an toàn). (H2 hỗ trợ `FOR UPDATE` hạn chế; chọn optimistic lock cho portable + đã quen từ Stage 2.)

**⭐ Exactly-once terminal transition (chống refund/settle KÉP — race worker × webhook × admin):** Nhiều actor có thể cùng terminal-hoá MỘT order đồng thời: reconciliation worker (slow path), bank webhook (fast path), admin resolve. "Guard terminal" kiểu *đọc state rồi mới act* là **check-then-act/TOCTOU** — nếu cả hai cùng đọc `SENT`, cả hai cùng qua guard, cả hai cùng `release` → **refund 2 lần (mất tiền thật)**. Quy tắc: **lật trạng thái + đổi tiền (settle/release) phải là MỘT thao tác nguyên tử (compare-and-swap), trong CÙNG một transaction, gated bởi `@Version`** — đúng pattern "người thắng/người thua" của idempotency race Stage 2. Chỉ transaction nào *lật được* `SENT→terminal` mới được đụng `wallet`; người thua `OptimisticLockException` → rollback → **không** có lần đổi tiền thứ hai. Mọi đường (worker/webhook/admin) BẮT BUỘC đi qua **một cửa duy nhất**: `WithdrawalSettlementService.applyTerminal(orderId, outcome)` (Task 4) → settle/refund đúng **exactly-once** bất kể bao nhiêu actor cùng tới.

**Bank là dependency NGOÀI (mock):** port `BankClient { TransferAck transfer(bankRef, amount, dest); BankStatus status(bankRef); }`. `MockBankClient` (profile dev/test, cấu hình được kết quả) + `RestBankClient` (RestClient + HMAC + breaker, cho e2e với một mock server). KHÔNG hiện thực ngân hàng thật.

---

## Cấu trúc thay đổi

```
wallet-service/
├── src/main/java/com/vng/wallet/
│   ├── domain/
│   │   ├── Wallet.java                      (Modify: + held, available(), reserve/settle/release)
│   │   ├── WalletTransaction.java           (Modify: Type += HOLD/SETTLED/REFUNDED)
│   │   ├── WithdrawalOrder.java             (Create: aggregate + state machine + guards)
│   │   ├── WithdrawalState.java             (Create: enum)
│   │   ├── InvalidWithdrawalTransitionException.java (Create)
│   │   ├── WithdrawalOrderRepository.java   (Create: PORT)
│   │   ├── BankClient.java                  (Create: PORT + TransferAck + BankStatus enum)
│   │   └── WalletRepository.java            (giữ; có thể gộp order repo hoặc tách — tách)
│   ├── application/
│   │   ├── WalletService.java               (Modify: withdraw -> tạo order + hold, trả order)
│   │   └── WithdrawalSettlementService.java (Create: ②③ settle/refund + dùng bởi worker & webhook)
│   └── infrastructure/
│       ├── persistence/ (WalletEntity +held · WithdrawalOrderEntity + SpringData + mapper)
│       ├── bank/ (BankClient adapter: RestBankClient, MockBankClient, BankConfig — reuse HmacSigner)
│       ├── scheduling/ReconciliationWorker.java   (Create: @Scheduled)
│       └── web/ (WalletController withdraw->202 + GET order status; WithdrawalWebhookController; AdminReviewController; GlobalExceptionHandler)
├── src/main/resources/application.yml       (Modify: wallet.bank.*, wallet.reconcile.*)
e2e/  (Modify: thêm kịch bản settle/crash/in-doubt với mock bank)
```

---

## Task 1: Domain — escrow trên `Wallet` + `WithdrawalOrder` state machine (E1, E2, E3)

**Files:**
- Modify: `domain/Wallet.java`, `domain/WalletTransaction.java`; (test) `domain/WalletTest.java`
- Create: `domain/WithdrawalState.java`, `domain/WithdrawalOrder.java`, `domain/InvalidWithdrawalTransitionException.java`; (test) `domain/WithdrawalOrderTest.java`

- [ ] **Step 1: Test `Wallet` available/held** (thêm vào `WalletTest`):

```java
@Test
void reserve_reducesAvailableNotBalance() {
    Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("100"), new BigDecimal("0"), 0L);
    w.reserve(new BigDecimal("30"));
    assertEquals(0, new BigDecimal("100").compareTo(w.getBalance()), "total không đổi");
    assertEquals(0, new BigDecimal("30").compareTo(w.getHeld()));
    assertEquals(0, new BigDecimal("70").compareTo(w.available()));
}
@Test
void reserve_rejectsWhenInsufficientAvailable() {
    Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("100"), new BigDecimal("80"), 0L); // held 80
    assertThrows(InsufficientFundsException.class, () -> w.reserve(new BigDecimal("30"))); // available 20
}
@Test
void settle_movesOutOfTotalAndHeld() {
    Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("100"), new BigDecimal("30"), 0L);
    w.settle(new BigDecimal("30"));
    assertEquals(0, new BigDecimal("70").compareTo(w.getBalance()));
    assertEquals(0, BigDecimal.ZERO.compareTo(w.getHeld()));
}
@Test
void release_returnsHeldToAvailable() {
    Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("100"), new BigDecimal("30"), 0L);
    w.release(new BigDecimal("30")); // refund
    assertEquals(0, new BigDecimal("100").compareTo(w.getBalance()));
    assertEquals(0, BigDecimal.ZERO.compareTo(w.getHeld()));
    assertEquals(0, new BigDecimal("100").compareTo(w.available()));
}
```

- [ ] **Step 2:** Run `cd wallet-service && mvn -q test -Dtest=WalletTest` → FAIL (compile).

- [ ] **Step 3: Sửa `Wallet`** — thêm `held` (ctor 6 tham số), `available()`, `reserve/settle/release`. Giữ `topup`/`withdraw`(nếu còn dùng nội bộ thì giữ; nếu không, có thể xoá sau khi Task 3 chuyển hết) / `getUserId` / `@Version`:

```java
private BigDecimal held;
public Wallet(Long id, String userId, String ownerName, BigDecimal balance, BigDecimal held, Long version) { ... }
public static Wallet createNew(String userId, String ownerName) {
    return new Wallet(null, userId, ownerName, BigDecimal.ZERO, BigDecimal.ZERO, null);
}
public BigDecimal getHeld() { return held; }
public BigDecimal available() { return balance.subtract(held); }

public void reserve(BigDecimal amount) {           // ① hold
    requirePositive(amount);
    if (amount.compareTo(available()) > 0) throw new InsufficientFundsException();
    this.held = this.held.add(amount);
}
public void settle(BigDecimal amount) {            // ③ settle: tiền rời hệ
    this.held = this.held.subtract(amount);
    this.balance = this.balance.subtract(amount);
}
public void release(BigDecimal amount) {           // ③ refund: trả hold về available
    this.held = this.held.subtract(amount);
}
```

- [ ] **Step 4: `WalletTransaction.Type`** → `{ TOPUP, WITHDRAW_HOLD, WITHDRAW_SETTLED, WITHDRAW_REFUNDED }`. (Sửa dây chuyền compile ở mọi nơi tham chiếu `Type.WITHDRAW` — sẽ xử lý trọn ở Task 3.)

- [ ] **Step 5: Test `WithdrawalOrder` (ma trận transition)** (`WithdrawalOrderTest`):

```java
@Test void newOrder_startsPending() {
    WithdrawalOrder o = WithdrawalOrder.create("user-1", 7L, new BigDecimal("30"), "k1", "ref-1");
    assertEquals(WithdrawalState.PENDING, o.getState());
}
@Test void pending_canMarkSent() { var o = pending(); o.markSent(); assertEquals(WithdrawalState.SENT, o.getState()); }
@Test void sent_canSettle()     { var o = sent(); o.markSettled(); assertEquals(WithdrawalState.SETTLED, o.getState()); }
@Test void sent_canFail()       { var o = sent(); o.markFailed("bad account"); assertEquals(WithdrawalState.FAILED, o.getState()); }
@Test void settled_cannotFail() { var o = settled(); assertThrows(InvalidWithdrawalTransitionException.class, () -> o.markFailed("x")); }
@Test void pending_cannotSettleDirectlyExceptViaReconcile() { /* định nghĩa rõ: cho phép PENDING->SETTLED (worker thấy bank đã settle) nhưng cấm PENDING->FAILED nếu chưa từng gửi? -> chốt theo design state machine §4 */ }
@Test void escalatesToManualReviewAfterThreshold() {
    var o = sent();
    for (int i=0;i<o /* maxAttempts */ .getMaxAttempts(); i++) o.recordUnknownAttempt();
    o.escalateIfExhausted();
    assertEquals(WithdrawalState.NEEDS_MANUAL_REVIEW, o.getState());
}
```

- [ ] **Step 6: Tạo `WithdrawalState`, `WithdrawalOrder`, `InvalidWithdrawalTransitionException`.** State machine theo design §4. `WithdrawalOrder` giữ: `id, userId, walletId, amount, state, bankRef, idempotencyKey, attemptCount, firstSentAt, version`. Guard mọi transition; `recordUnknownAttempt()` tăng `attemptCount`; `escalateIfExhausted()` chuyển `SENT→NEEDS_MANUAL_REVIEW` khi `attemptCount > N` hoặc `now − firstSentAt > T` (ngưỡng truyền vào hoặc hằng số tạm). Transition hợp lệ (cấm phần còn lại):

```
PENDING -> SENT | SETTLED(worker thấy đã settle) | FAILED(bank từ chối ngay khi gửi)
SENT    -> SETTLED | FAILED | NEEDS_MANUAL_REVIEW | SENT(query lại)
NEEDS_MANUAL_REVIEW -> SETTLED | FAILED   (admin)
SETTLED, FAILED = terminal
```

- [ ] **Step 7:** Run `mvn -q test -Dtest=WalletTest,WithdrawalOrderTest` → PASS.
- [ ] **Step 8:** `git commit -m "feat(wallet): escrow balance (available/held) + WithdrawalOrder state machine (E1,E2,E3)"`

---

## Task 2: Persistence — `WithdrawalOrder` + `held` column (E2, E6, E7)

**Files:**
- Modify: `persistence/WalletEntity.java` (+ `held`), `WalletMapper.java`
- Create: `persistence/WithdrawalOrderEntity.java`, `SpringDataWithdrawalOrderJpa.java`, `JpaWithdrawalOrderRepository.java`, `domain/WithdrawalOrderRepository.java` (PORT), mapper cho order
- Modify (test): `persistence/JpaWalletRepositoryTest.java`; Create `persistence/JpaWithdrawalOrderRepositoryTest.java`

PORT:
```java
public interface WithdrawalOrderRepository {
    WithdrawalOrder save(WithdrawalOrder order);
    Optional<WithdrawalOrder> findByIdempotencyKey(String key);   // replay (E7 tầng user)
    Optional<WithdrawalOrder> findByBankRef(String bankRef);
    Optional<WithdrawalOrder> findByIdAndUserId(Long id, String userId); // poll status (scoped, D2)
    List<WithdrawalOrder> findReconcilable(int limit);            // state IN (PENDING,SENT) ORDER BY updatedAt
}
```

- [ ] **Step 1: Test** (`JpaWithdrawalOrderRepositoryTest`): save→findByIdempotencyKey; `idempotency_key` và `bank_ref` UNIQUE (insert trùng → `DataIntegrityViolationException`); `findReconcilable` chỉ trả PENDING/SENT (không trả SETTLED/FAILED/REVIEW); `findByIdAndUserId` scoped.
- [ ] **Step 2:** `mvn -q test -Dtest=JpaWithdrawalOrderRepositoryTest` → FAIL.
- [ ] **Step 3:** `WalletEntity` + cột `held NUMERIC NOT NULL DEFAULT 0`; cập nhật `WalletMapper` (MapStruct — `available` là dẫn xuất, KHÔNG map). `WithdrawalOrderEntity` với `@Version`, unique constraints. Repository adapter + mapper. `findReconcilable` dùng `@Query` lọc state + `Pageable`/`limit`.
- [ ] **Step 4:** `mvn -q test` (persistence) → PASS.
- [ ] **Step 5:** `git commit -m "feat(wallet): persist WithdrawalOrder + held column, scoped + reconcilable queries"`

---

## Task 3: Withdraw use case → tạo order PENDING + hold (① atomic) + trả 202 (E3, E5 bước ①)

> Đây là chỗ **thay thế đường withdraw cũ**. Bank CHƯA gọi (order dừng ở PENDING, worker Task 4–5 sẽ lái tiếp). Cổng KYC (SP3), idempotency replay, scoped AuthZ — GIỮ NGUYÊN, chỉ đổi phần "áp tiền".

**Files:** Modify `application/WalletService.java`, `web/WalletController.java`, `web/dto/*` (thêm `WithdrawalOrderResponse`), `web/GlobalExceptionHandler.java`; cập nhật test `WalletServiceTest`, `WalletControllerTest`, integration tests.

- [ ] **Step 1: Test use case** (`WalletServiceTest`): `withdraw(...)` giờ trả `WithdrawalOrder` ở state `PENDING`; ví bị `reserve` (available giảm, balance chưa đổi); ledger có 1 bút toán `WITHDRAW_HOLD`; KYC `DENIED`→403 (không tạo order, không hold); replay cùng `Idempotency-Key`→trả order cũ (không hold lần 2); `available` không đủ→`InsufficientFundsException` (422).
- [ ] **Step 2:** `mvn -q test -Dtest=WalletServiceTest` → FAIL.
- [ ] **Step 3: Sửa `WalletService.withdraw`** — sau cổng KYC, trong `txTemplate.execute`:
  1. replay theo `idempotency_key` (order tồn tại → trả, requireMatching userId/walletId/amount như cũ);
  2. `wallet.reserve(amount)` (ném InsufficientFunds nếu thiếu available);
  3. sinh `bankRef` (vd `"wd-" + orderId` sau khi save, hoặc UUID truyền sẵn — **phải nằm trong cùng tx**, E7);
  4. `save(wallet)` + `save(WithdrawalOrder.create(...PENDING, bankRef))` + ledger `WITHDRAW_HOLD`.
  
  Giữ `executeWithIdempotentRecovery`/race-recovery (DIVE→đọc lại order→409) tương tự bút toán cũ, nhưng key đua giờ là `idempotency_key` của order.
- [ ] **Step 4: Controller** — `POST /wallets/{id}/withdraw` trả **`202 Accepted`** + `WithdrawalOrderResponse{orderId, state, amount}` (E1, không còn 200). Topup giữ 200. Thêm `GET /wallets/{id}/withdrawals/{orderId}` → trạng thái (scoped, D2). `GlobalExceptionHandler`: `InsufficientFundsException`→422 (giữ), `InvalidWithdrawalTransitionException`→409.
- [ ] **Step 5:** Cập nhật `WalletControllerTest` + integration (`WalletKycGateIntegrationTest`, `WalletLedgerIntegrationTest`, `WalletConcurrencyIntegrationTest`): withdraw kỳ vọng 202 + order; số dư = available/held; ledger `WITHDRAW_HOLD`. **Đây là drift có chủ đích** — mọi assertion "withdraw trừ balance ngay / trả 200" phải đổi sang ngữ nghĩa escrow.
- [ ] **Step 6:** `mvn -q test` → PASS toàn bộ.
- [ ] **Step 7:** `git commit -m "feat(wallet): withdraw creates PENDING order + escrow hold, returns 202 (E1,E3)"`

---

## Task 4: `BankClient` port + adapter + settle/refund (② + ③ happy path, E5, E8)

**Files:** Create `domain/BankClient.java`, `infrastructure/bank/{MockBankClient,RestBankClient,BankConfig}.java`, `application/WithdrawalSettlementService.java`; (test) `infrastructure/bank/RestBankClientTest.java`, `application/WithdrawalSettlementServiceTest.java`.

PORT:
```java
public interface BankClient {
    TransferAck transfer(String bankRef, BigDecimal amount /*, dest*/);  // ②
    BankStatus  status(String bankRef);                                  // E8 query
    enum BankStatus { SETTLED, REJECTED, UNKNOWN }   // "unknown != failed" (E9)
    record TransferAck(BankStatus result) {}
}
```

> **Cửa nguyên tử duy nhất — `applyTerminal(orderId, outcome)`:** mọi đường settle/refund (worker, webhook, admin) đi qua DUY NHẤT method này. Trong CÙNG `txTemplate.execute`: (1) reload order theo id (kèm `@Version`); (2) nếu state đã terminal → **no-op return** (idempotent tuần tự); (3) còn non-terminal → lật state (`markSettled`/`markFailed`) + `wallet.settle`/`wallet.release` + ghi ledger; (4) save order (bump `@Version`) + save wallet. Hai actor đua: cả hai reload ở version V, người đầu commit (V→V+1), người thứ hai save đụng `OptimisticLockException` → **rollback toàn bộ tx → không có lần đổi tiền thứ hai** (exactly-once). Cú gọi bank `transfer`/`status` nằm NGOÀI tx (E5); chỉ phần lật-state-đổi-tiền vào trong `applyTerminal`.

- [ ] **Step 1: Test `WithdrawalSettlementService`** (fake `BankClient`):
  - `processSend(order)`: gọi `transfer` (ngoài tx) → SETTLED → `applyTerminal(SETTLED)`: `wallet.settle` + ledger `WITHDRAW_SETTLED` + order `SETTLED`; available không đổi, balance giảm.
  - REJECTED → `applyTerminal(FAILED)`: `wallet.release` + ledger `WITHDRAW_REFUNDED` + order `FAILED`; available phục hồi.
  - UNKNOWN/timeout → order ở `SENT`, **KHÔNG** gọi `applyTerminal` (E9), `recordUnknownAttempt`.
  - **Idempotent tuần tự:** `applyTerminal(orderId, FAILED)` hai lần liên tiếp → lần 2 thấy đã terminal → no-op (balance không đổi kép).
  - **Exactly-once đồng thời (race worker × webhook):** hai luồng cùng gọi `applyTerminal(orderId, FAILED)` trên order còn `SENT` (mô phỏng bằng cách giữ hai instance order cùng version V rồi save lần lượt; lần 2 phải ném `OptimisticLockException` và **không** áp `release` lần hai) → khẳng định `wallet.held`/`available` chỉ đổi MỘT lần.
- [ ] **Step 2:** `mvn -q test -Dtest=WithdrawalSettlementServiceTest` → FAIL.
- [ ] **Step 3:** Cài `WithdrawalSettlementService` với `applyTerminal` như mô tả ở trên (CAS atomic dưới `@Version`, cửa duy nhất). `processSend`/đường webhook/đường admin đều gọi `applyTerminal`. Dùng cùng `bankRef` của order (E7). Người thua `OptimisticLockException` → nuốt + log (không phải lỗi, là kết quả mong đợi của race).
- [ ] **Step 4: `MockBankClient`** (`@Profile({"dev","test"})` hoặc `@ConditionalOnProperty wallet.bank.mock=true`): trả kết quả cấu hình được (map bankRef→kịch bản) để e2e dựng SETTLED/REJECTED/UNKNOWN. `RestBankClient`: RestClient + read-timeout 2s + Resilience4j breaker (tái dùng cấu hình kiểu SP3) + HMAC ký (`HmacSigner` có sẵn, canonical chung). `RestBankClientTest` dùng MockWebServer: SETTLED/REJECTED/timeout→UNKNOWN; breaker mở sau N lỗi.
- [ ] **Step 5:** `mvn -q test` → PASS.
- [ ] **Step 6:** `git commit -m "feat(wallet): BankClient port + settle/refund (idempotent ③), breaker + HMAC adapter (E5,E8)"`

---

## Task 5: Reconciliation worker — self-healing (E6, E8)

**Files:** Create `infrastructure/scheduling/ReconciliationWorker.java`; (test) `application/ReconciliationServiceTest.java` (+ logic tách ra service nếu cần để test không phụ thuộc scheduler), integration `WithdrawalReconciliationIntegrationTest`.

- [ ] **Step 1: Test** (fake bank + repo thật/in-memory):
  - order `PENDING` (crash trước ②): worker `status(bankRef)`=UNKNOWN-"chưa thấy" → gọi `transfer` cùng bankRef → SETTLED. (Crash-trước-② recovery.)
  - order `SENT`, bank `status`=SETTLED (crash sau khi bank đã nhận): → ③ settle, **KHÔNG** gọi `transfer` lại. (Chống trả kép E7.)
  - order `SENT`, bank UNKNOWN: giữ SENT, `attemptCount++`.
  - order `SETTLED`/`FAILED`: worker bỏ qua (`findReconcilable` không trả).
  - hai worker chạy đồng thời trên cùng order: chỉ một thành công (`@Version` → người thua `OptimisticLockException`, nuốt + log).
- [ ] **Step 2:** `mvn -q test -Dtest=ReconciliationServiceTest` → FAIL.
- [ ] **Step 3:** Cài service: `findReconcilable(limit)` → mỗi order: nếu `PENDING` chưa từng gửi (attempt 0 / firstSentAt null) → `processSend`; nếu `SENT` → `status(bankRef)` rồi settle/refund/giữ. Bọc mỗi order trong try/catch (một order lỗi không chặn order khác). `ReconciliationWorker` = `@Scheduled(fixedDelayString = "${wallet.reconcile.interval-ms:30000}")` gọi service; bật bằng `@ConditionalOnProperty wallet.reconcile.enabled=true` + `@EnableScheduling`.
- [ ] **Step 4: Integration** (`@SpringBootTest` + MockBankClient + Awaitility): tạo order PENDING, để worker chạy, `await().untilAsserted(... SETTLED ...)`.
- [ ] **Step 5:** `mvn -q test` → PASS.
- [ ] **Step 6:** `git commit -m "feat(wallet): reconciliation worker drives orders to terminal, self-healing + @Version (E6,E8)"`

---

## Task 6: UNKNOWN vs FAILED + ngưỡng → NEEDS_MANUAL_REVIEW + admin resolve (E9, E10)

**Files:** Modify `WithdrawalOrder` (ngưỡng đã có từ Task 1 — nối vào worker), `ReconciliationService`; Create `web/AdminReviewController.java`; (test) bổ sung `ReconciliationServiceTest`, `AdminReviewControllerTest`.

- [ ] **Step 1: Test:**
  - order `SENT` UNKNOWN quá `N` lần (hoặc quá `T`) → worker `escalateIfExhausted()` → `NEEDS_MANUAL_REVIEW`; **KHÔNG** auto refund/settle; tiền vẫn `held` (đóng băng).
  - `findReconcilable` KHÔNG trả order `NEEDS_MANUAL_REVIEW` (worker ngừng đụng — chờ người).
  - admin `POST /admin/withdrawals/{orderId}/resolve {decision: SETTLED|FAILED}` → áp settle/refund tương ứng + chuyển terminal. (AuthZ: yêu cầu role/HMAC — tái dùng cơ chế X-Roles như kyc revoke; scope plan: kiểm header role `ops`/`compliance`.)
  - "timeout không refund": dựng bank luôn UNKNOWN, chạy worker tới ngưỡng → REVIEW, khẳng định `wallet.release` KHÔNG được gọi.
- [ ] **Step 2:** `mvn -q test` → FAIL → cài → PASS.
- [ ] **Step 3:** `git commit -m "feat(wallet): in-doubt -> NEEDS_MANUAL_REVIEW after threshold + admin resolve, never auto-refund on unknown (E9,E10)"`

---

## Task 7: Fast path webhook (bank → wallet) + poll status (E5 fast path)

**Files:** Create `web/WithdrawalWebhookController.java` (+ verify HMAC secret RIÊNG cho bank, như webhook verifier KYC); (test) `WithdrawalWebhookControllerTest`.

- [ ] **Step 1: Test:** `POST /webhooks/bank/settlement {bankRef, result}` ký HMAC bank → áp settle/refund qua `WithdrawalSettlementService.applyTerminal` (cửa nguyên tử chung). Trả **200** cho: hợp lệ (APPLIED), order đã terminal (DUPLICATE/no-op), bankRef lạ (IGNORED) — **không 4xx** (tránh bank retry vô hạn, đúng bài học webhook KYC). Sai chữ ký → 401.
- [ ] **Step 2:** `mvn -q test` → FAIL → cài → PASS.
- [ ] **Step 3: ⭐ Test đua worker × webhook (exactly-once đầu-cuối)** (`WithdrawalRaceIntegrationTest`, `@SpringBootTest` + order `SENT` thật): kích hoạt **đồng thời** đường worker (`processSend`/reconcile cho order) và đường webhook (`applyTerminal` cùng outcome) trên cùng một order — vd hai thread/`CompletableFuture` cùng chạy. Khẳng định: order về terminal đúng MỘT lần; `wallet.held` giảm đúng MỘT lần; ledger có đúng MỘT bút toán `WITHDRAW_SETTLED`/`WITHDRAW_REFUNDED` (không kép); một trong hai luồng nuốt `OptimisticLockException` (log, không lỗi ra ngoài).
- [ ] **Step 4:** `git commit -m "feat(wallet): bank settlement webhook (fast path) + exactly-once vs worker race (HMAC, 200-on-duplicate)"`

> Worker (Task 5, slow path) + webhook (fast path) + admin (Task 6) dùng CHUNG `applyTerminal` → CAS atomic dưới `@Version` đảm bảo settle/refund **exactly-once** dù cả ba cùng tới. Đây là chỗ đóng kín race "refund kép" — guard "đọc-rồi-act" KHÔNG đủ, phải là lật-state-và-đổi-tiền nguyên tử trong một tx.

---

## Task 8: Integration + E2E thật (gateway + wallet + mock bank + Kafka)

**Files:** Modify `e2e/scenario.sh` (+ kịch bản settlement), `application.yml`; README e2e.

- [ ] **Step 1:** Ma trận §11 design dưới dạng integration test (`@SpringBootTest` + MockBankClient): happy settle, reject→refund, crash-trước-②, crash-sau-②, timeout-không-refund, in-doubt→review, webhook trùng, double-spend (rút 2 lần cùng available → lần 2 thấy held → 422).
- [ ] **Step 2: e2e thật** — thêm vào `scenario.sh` sau bước withdraw: rút → nhận **202** + orderId → (MockBank cấu hình SETTLED) → poll `GET .../withdrawals/{orderId}` tới `SETTLED` (Awaitility kiểu bash: vòng lặp curl + sleep) → khẳng định `available`/`balance` đúng. Một kịch bản REJECTED → poll tới `FAILED` → available phục hồi.
- [ ] **Step 3:** Chạy 3 service + mock bank + Kafka; xác nhận PASS. Dọn (kill PID giữ cổng — bài học orphan process).
- [ ] **Step 4:** `git commit -m "test(sp4): integration matrix + real e2e settlement (settle/reject/crash/in-doubt)"`

---

## Nợ kỹ thuật mang theo (nhắc lại từ design §10)
- Ngân hàng = mock; thật sẽ thay `RestBankClient` thật + outbox cho event.
- `X-User-Id` chưa verify HMAC tại wallet (Stage 4); `shared-hmac` lib (giờ canonical đã dùng ở 4 nơi: gateway, kyc, RestKycGate, RestBankClient).
- Backoff cố định (chưa exponential+jitter); admin resolve qua API (chưa có UI).
- Observability nấc 2 (OpenTelemetry) cho span độ trễ cú gọi bank ② — nền `X-Trace-Id` đã có.

## Checklist Done
- [ ] withdraw trả 202 + order PENDING; available/held đúng; ledger HOLD/SETTLED/REFUNDED.
- [ ] worker tự lái PENDING/SENT → terminal; self-healing sau crash; không trả kép tới bank (cùng bankRef).
- [ ] **exactly-once terminal:** worker × webhook × admin đua nhau → settle/refund đúng MỘT lần (CAS atomic dưới @Version qua `applyTerminal`).
- [ ] "unknown ≠ failed": timeout không refund; quá ngưỡng → NEEDS_MANUAL_REVIEW → admin.
- [ ] webhook fast path idempotent, 200-on-duplicate; poll status cho user.
- [ ] e2e thật xanh: settle + reject(refund) qua mock bank.
- [ ] toàn bộ test xanh (wallet + gateway + kyc); git tree clean.
