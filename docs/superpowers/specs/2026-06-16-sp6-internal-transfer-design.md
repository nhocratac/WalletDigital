# Thiết kế: SP6 — Internal Transfer (chuyển tiền ví → ví)

- **Ngày:** 2026-06-16
- **Phạm vi:** Thêm thao tác **chuyển tiền giữa hai ví trong hệ** (A → B) vào `wallet-service`. Lần đầu một giao dịch **đụng HAI ví** → bài toán mới: atomicity 2 dòng + concurrency (deadlock / lock-ordering). KHÔNG có chân ngoài (không bank, không escrow).
- **Tiền đề:** SP1–SP5 ✅ (ledger, escrow/withdraw, KYC gate, delayed settlement, multi-tenant — 262 test). Tái dùng: `@Version` optimistic + `executeWithIdempotentRecovery` (Stage 2), scoped query D2 (SP3), `TenantContext` (SP5), `KycGate` (SP3).
- **Mục tiêu học:** atomicity nhiều dòng, double-entry, deadlock & lock-ordering, optimistic vs pessimistic locking (đánh đổi), KYC ở góc AML/layering, IDOR cho thao tác 2-bên, idempotency một-biên-một-key.

> **Nguồn gốc:** quyết định TR1–TR8 do **CHÍNH NGƯỜI HỌC suy ra** trong phiên Socratic (anh chỉ hỏi & phản biện). Mỗi quyết định kèm "lý do đã tự suy ra". Diagram render ở file `.html` cùng tên (ASCII trong MD này để đọc trên terminal).

---

## 1. Bối cảnh & Vấn đề

User A có ví, muốn **chuyển X cho user B** (cùng hệ). Đây là thao tác đầu tiên **đụng hai ví** — topup/withdraw chỉ đụng *một* ví (cộng escrow). Hai cái mới sinh ra:

1. **Atomicity nhiều dòng:** A trừ + B cộng phải *cùng xảy ra hoặc cùng không* — nếu làm hai bước rời, crash giữa chừng → **tiền bốc hơi** (A đã trừ, B chưa cộng) → vi phạm bảo toàn tiền (sổ không khớp).
2. **Concurrency:** một transaction giờ khóa **hai** dòng → mở ra **deadlock** khi hai transfer ngược chiều chạy đồng thời.

Nhưng transfer cũng **ĐƠN GIẢN hơn withdraw** một bậc trên mọi trục: tiền **không rời hệ** (chỉ đổi chủ) → **không** cần escrow, **không** reconciliation worker, **không** "unknown≠failed", **không** key thứ hai cho bank.

---

## 2. Bảng quyết định (TR1–TR8 từ phiên Socratic)

| # | Quyết định | Lý do (đã tự suy ra) |
|---|---|---|
| TR1 | Transfer = **một transaction ACID**, ghi **double-entry**: 1 bút toán `TRANSFER_OUT` (trừ A) + 1 `TRANSFER_IN` (cộng B), cùng commit. | Crash giữa hai bước rời → tiền bốc hơi (inconsistency). Gói chung một transaction → cả hai cùng rollback → tổng bảo toàn *bằng cấu trúc*. |
| TR2 | Concurrency: **optimistic `@Version` + retry giới hạn** làm MẶC ĐỊNH; **pessimistic + lock-ordering theo `wallet_id`** để dành cho ví "nóng" khi *đo được*. | Optimistic không giữ khóa → không deadlock, chỉ retry khi đụng; transfer ngẫu nhiên A→B ít tranh chấp nên thắng phần lớn. Pessimistic giữ khóa → deadlock nếu khóa ngược thứ tự → phải lock-ordering theo id để phá circular wait. Bắt đầu đơn giản, nâng cấp khi đo. |
| TR3 | **Intra-tenant only** — A và B buộc cùng tenant. | Nghiệp vụ: hai tenant = hai *công ty* riêng ("kho ví riêng"), chuyển giữa chúng sai mô hình. Kỹ thuật (SP5): một connection ↔ một schema → KHÔNG `UPDATE` hai schema trong một tx → cross-tenant transfer *bất khả về cấu trúc*. |
| TR4 | **Bên GỬI cần KYC APPROVED**; **bên NHẬN không cần**. | Layering/AML: nếu transfer-out không gác KYC, kẻ chưa-KYC chuyển tiền sang ví đã-KYC (mule) rồi mule rút → cổng KYC ở withdraw vô dụng. Transfer-out = *đẩy value khỏi tầm kiểm soát* = cùng lớp rủi ro withdraw. Nhận = "nhận tự do" (just-in-time KYC, R1) — mule chỉ bị chặn khi *chính nó* đi rút. |
| TR5 | Ví **GỬI** scoped theo caller (`findByIdAndUserId` — D2) → sai chủ = 404. Ví **NHẬN** load **theo id** + **cùng tenant**, KHÔNG scope theo caller. | Caller chỉ được rút tiền từ ví *của mình* (chống trộm: "gửi từ ví người khác"). Ví nhận không thuộc caller (đang gửi cho người khác) nên không scope được — chỉ cần tồn tại + cùng tenant. |
| TR6 | Chặn **self-transfer** (`from == to`) → 400. | Vô nghĩa (net 0); nếu lock theo wallet_id thì hai "ví" là cùng một dòng → debit+credit cùng row, dễ bug/đụng version; thường là lỗi/lạm dụng. |
| TR7 | **MỘT** idempotency-key (client → wallet), do **client sinh** (UUID), cột UNIQUE, replay trả transfer cũ. | Thao tác mutate tiền + client retry → không key thì retry chuyển 2 lần. Chỉ **một biên** retry (client→wallet); transfer KHÔNG có chân ngoài nên KHÔNG cần key thứ hai (khác withdraw có thêm `bankRef` cho bank). E7: mỗi biên-retry một key; transfer chỉ một biên. |
| TR8 | KHÔNG escrow / worker / "unknown≠failed" / fast-path webhook. | Tiền ở lại hệ, không gọi ngoài → không có vùng rủi ro bất đồng bộ để nhốt. Transfer đồng bộ, hoàn tất ngay trong một transaction. |

---

## 3. Luồng transfer & ranh giới

```
POST /wallets/{fromId}/transfer
   body: { toWalletId, amount }   header: X-User-Id=caller, X-Tenant-Id (SP5), Idempotency-Key=K
   │
   ▼ [0] validate: amount > 0 ; fromId != toWalletId (TR6 — chặn self-transfer) ; key không blank
   ▼ [1] Idempotency replay (NGOÀI tx): key K đã có -> trả transfer cũ, DỪNG          (TR7)
   ▼ [2] CỔNG KYC bên GỬI (NGOÀI tx — D4, no remote in tx): caller APPROVED?           (TR4)
   │       không -> 403 ; breaker mở/timeout -> 503   (tái dùng KycGate SP3)
   ▼ [3] ═══ TRANSACTION (ngắn, nội bộ, không network) ═══                              (TR1)
   │     - load ví GỬI scoped:  WHERE id=fromId AND user_id=caller   -> 404 nếu sai     (TR5)
   │     - load ví NHẬN:        WHERE id=toWalletId (cùng tenant qua routing SP5)        (TR5, TR3)
   │           không tồn tại -> 404/422 "recipient not found"
   │     - from.balance < amount -> InsufficientFunds (422)
   │     - from.withdrawHold/debit(amount) ; to.credit(amount)        (đổi @Version cả hai)
   │     - ghi 2 bút toán: TRANSFER_OUT(from) + TRANSFER_IN(to), cùng transferId + key K
   ▼ [4] commit (cả hai dòng + 2 bút toán) -> trả TransferResponse
        (đụng @Version -> OptimisticLockException -> retry giới hạn; hết -> 409)         (TR2)
```

> **TR3 enforced miễn phí bởi SP5:** transaction chạy dưới `TenantContext` của caller → connection trỏ đúng một schema tenant. Ví nhận ở tenant khác **không tồn tại** trong schema đó → load ra rỗng → 404. Không cần check tenant thủ công; routing tự chặn.

---

## 4. Concurrency — deadlock & lock-ordering (TR2)

### Deadlock (chỉ xảy ra với khóa BI QUAN)
```
   T1 (A→B)                T2 (B→A)  — đồng thời
   khóa A ✔                khóa B ✔
   xin B … chờ  ┐      ┌── xin A … chờ
               ▼      ▼
        B bị T2 giữ   A bị T1 giữ   → vòng chờ tròn → DEADLOCK
```
**Phá bằng lock-ordering:** luôn khóa theo `ORDER BY wallet_id` (id nhỏ trước), bất kể chiều chuyển. → cả T1 lẫn T2 đều giành id nhỏ trước → không còn vòng tròn.

### Nhưng mặc định của em là OPTIMISTIC (`@Version`) → KHÔNG deadlock
Optimistic không giữ khóa; phát hiện xung đột lúc commit (version lệch) → một bên `OptimisticLockException` → **retry** (đọc lại, áp lại). Đánh đổi:

```
                OPTIMISTIC (@Version)        PESSIMISTIC (FOR UPDATE)
deadlock        bất khả                       có thể -> cần lock-ordering
khi đụng        retry (1 bên thua version)    chờ khóa
hợp khi         ít tranh chấp (mặc định)      ví "nóng" (đo được)
```

**Chọn:** optimistic + retry giới hạn (nhất quán Stage 2, đơn giản), nâng pessimistic+lock-ordering cho ví nóng khi *đo được*. Dù cách nào, load/xử lý hai ví theo **thứ tự wallet_id** cho dễ suy luận.

---

## 5. Mô hình dữ liệu & ledger (TR1)

```
WalletTransaction.Type  +=  TRANSFER_OUT, TRANSFER_IN     (đã có TOPUP, WITHDRAW_HOLD/SETTLED/REFUNDED)

Một transfer logic = 2 bút toán bất biến, cùng:
  transfer_id        (nhóm cặp double-entry lại — đối soát)
  idempotency_key K  (UNIQUE — TR7)
  TRANSFER_OUT: wallet=from, amount=-X (balanceAfter của from)
  TRANSFER_IN : wallet=to,   amount=+X (balanceAfter của to)
```
Bất biến hệ thống: mỗi transfer là một cặp DEBIT+CREDIT cân bằng → `Σ balance` toàn tenant không đổi qua transfer (tiền chỉ dịch chỗ).

> **Idempotency key đặt ở đâu:** key K map tới **cả transfer** (một record/một key), kiểm trong **cùng transaction** với 2 bút toán. Replay → trả transfer cũ, KHÔNG áp lại debit/credit. Client sinh K (UUID), gửi qua gateway (gateway forward header — nhớ bug e2e từng nuốt `Idempotency-Key`). Server chỉ ép UNIQUE, không tự chế key.

---

## 6. Hợp đồng lỗi

| Tình huống | HTTP |
|---|---|
| Transfer OK | 200 `{transferId, from, to, amount}` |
| `from == to` (self-transfer) | 400 (TR6) |
| Thiếu/blank Idempotency-Key, amount xấu | 400 |
| Ví gửi không tồn tại / không thuộc caller | 404 (D3 — giấu tồn tại) (TR5) |
| Ví nhận không tồn tại (hoặc khác tenant) | 404/422 "recipient not found" (TR3, TR5) |
| Bên gửi KYC ≠ APPROVED | 403 (TR4) |
| KYC không kiểm được (breaker/timeout) | 503 + Retry-After (TR4) |
| Không đủ tiền | 422 |
| Đụng optimistic lock sau retry giới hạn | 409 "thử lại" (TR2) |
| Cùng key, khác payload | 409 (IdempotencyKeyConflict — Stage 2) |

---

## 7. Nợ kỹ thuật & YAGNI

**Nợ:** `X-User-Id`/`X-Tenant-Id` chưa verify HMAC (wallet Stage 4); chưa áp transaction limit/velocity (ứng viên SP riêng — chống gian lận).

**YAGNI (không làm ở SP6):**
- Hạn mức/velocity limit khi chuyển (để SP riêng).
- Thông báo cho người nhận (event/notification).
- Transfer cross-tenant qua cơ chế settlement (khác hẳn atomic transfer — chỉ ghi nhận là *không* thuộc transfer).
- Transfer có "nhận/từ chối" (push model, credit ngay; không pull).
- Pessimistic locking (chỉ thêm khi đo được ví nóng).

---

## 8. Chiến lược kiểm thử

```
Unit (domain/application, fake KycGate):
  · transfer trừ from + cộng to ĐÚNG; tổng balance không đổi
  · 2 bút toán TRANSFER_OUT/IN cùng transferId; balanceAfter đúng
  · self-transfer (from==to) -> 400
  · from không đủ tiền -> 422, KHÔNG ghi bút toán nào
  · KYC bên gửi DENIED -> 403 (không đụng tiền); UNAVAILABLE -> 503
  · replay cùng Idempotency-Key -> trả transfer cũ, KHÔNG chuyển lần hai
  · cùng key khác payload -> 409
Persistence/scoped (D2):
  · ví gửi của user khác -> 404 (không chuyển được TỪ ví người khác)
  · ví nhận chỉ cần tồn tại (không scope theo caller)
Concurrency:
  · hai transfer đồng thời đụng cùng ví -> một thắng, một retry/409 (không mất/nhân tiền)
  · (nếu pessimistic) A→B và B→A đồng thời -> KHÔNG deadlock nhờ lock-ordering
Multi-tenant (SP5, Testcontainers MySQL):
  · transfer tới ví "cùng id nhưng khác tenant" -> 404 (routing chặn, TR3)
Integration/e2e:
  · A topup -> transfer cho B -> B balance tăng, A giảm, tổng bảo toàn; A chưa KYC -> 403
```

---

## 9. Lộ trình triển khai đề xuất (TDD)

1. **Domain:** `Wallet.debit/credit` (hoặc tái dùng withdraw/topup nội bộ); `WalletTransaction.Type += TRANSFER_OUT/IN`; validate self-transfer.
2. **Use case `transfer`** trong `WalletService`: replay (TR7) → KYC gate bên gửi NGOÀI tx (TR4) → transaction: load gửi scoped + nhận by-id → debit/credit + 2 bút toán + `@Version` retry (TR1, TR2, TR5). Unit test ma trận §8.
3. **Controller** `POST /wallets/{fromId}/transfer` + DTO + `GlobalExceptionHandler` (404/422/403/409). Forward `Idempotency-Key` (đã có ở gateway).
4. **Concurrency test** (đồng thời) + **multi-tenant test** (TR3, Testcontainers MySQL) + integration/e2e.
5. *(tùy chọn sau)* pessimistic + lock-ordering cho ví nóng nếu đo được.
