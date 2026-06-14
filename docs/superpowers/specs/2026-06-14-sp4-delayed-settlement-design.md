# Thiết kế: SP4 — Delayed Settlement (Withdraw vòng đời bất đồng bộ + đối soát)

- **Ngày:** 2026-06-14
- **Phạm vi:** Sửa `wallet-service` — biến `withdraw` từ một thao tác tức thời thành **state machine có vòng đời**, thêm **escrow account**, **reconciliation worker**, và một **dependency ngân hàng bên ngoài (mock)**. KHÔNG tạo service tiền tệ mới; ngân hàng là hệ ngoài, mình chỉ mô phỏng.
- **Tiền đề:** SP1 ✅ (withdraw + ledger) · SP2 ✅ (kyc state machine) · SP3 ✅ (cổng KYC + Kafka, e2e 7/7).
- **Mục tiêu học:** state machine cho tiền, escrow / available-vs-total balance, double-entry bookkeeping, in-doubt transactions, reconciliation worker (self-healing), idempotency đa tầng, "unknown ≠ failed", dead-letter / manual review queue.

> **Nguồn gốc tài liệu này:** toàn bộ các quyết định cốt lõi (E1–E10) đều do **CHÍNH NGƯỜI HỌC suy ra** trong phiên brainstorm Socratic (anh chỉ hỏi & phản biện, không đưa đáp án/option). Mỗi quyết định ghi kèm "lý do đã tự suy ra". Các con số cấu hình (ngưỡng retry, backoff…) là đề xuất của Claude để duyệt. Diagram render được nằm ở file `.html` cùng tên.

---

## 1. Bối cảnh & Vấn đề

Yêu cầu gốc (R-settle): *user rút tiền → tiền phải rời hệ thống ví đi ra ngân hàng thật. Cú chuyển ra ngoài CHẬM (giây→phút) và CÓ THỂ THẤT BẠI.*

`withdraw` hiện tại (sau SP3) coi rút tiền là **một sự kiện tức thời, nguyên tử**: kiểm tra số dư → trừ ngay → ghi ledger → trả `200 OK`. **Xong.**

Nhưng tới giây phút trả `200 OK`, **chưa một đồng nào rời khỏi đâu cả**. Tiền thật chỉ đi khi wallet gọi ra **payment gateway / ngân hàng**, mà cú đó *mất thời gian* và *có thể fail*.

### Lỗi gốc rễ: mô hình sai

Trừ tiền **trước khi** biết kết quả ngân hàng tạo ra hai loại lỗi, **cả hai đều không thể chấp nhận** trong domain tiền tệ:

```
(a) Trừ ngay rồi gọi bank, bank FAIL  -> sổ ghi "đã rút 100k", tiền thật vẫn nguyên.
                                          ==> SỔ CÁI KHÔNG KHỚP THỰC TẾ (the books don't reconcile)
(b) Chờ bank xong mới trừ, lúc PENDING -> tiền vẫn nằm trong available.
                                          ==> user bấm rút LẦN NỮA cùng số tiền đó (DOUBLE-SPEND)
```

> **Insight nền (bài học SA):** khi **hai lựa chọn đều sai**, đừng chọn "cái ít sai hơn" — hãy **nghi ngờ GIẢ ĐỊNH CHUNG của cả hai**. Giả định chung của (a) và (b) là *"chỉ có MỘT tài khoản: ví của user"*. Phá giả định đó (thêm tài khoản thứ 2 — **escrow**) thì thế lưỡng nan tan biến: tiền có chỗ thứ ba để "ở" — *đang trên đường đi*.

### Đây KHÔNG phải payment gateway

Mình xây **ví (digital wallet / ledger)** — nguồn sự thật về số dư nội bộ. Ví **gọi tới** payment gateway/ngân hàng (hệ ngoài) để đẩy tiền ra. SP4 là về *xử lý đúng khi cú gọi-ra-ngoài đó chậm và có thể fail* — ngân hàng chỉ là **dependency mock**, không hiện thực.

```
   User ─JWT─► [api-gateway] ─HMAC+X-User-Id─► [wallet-service] ──┐
                                                  │ escrow + state │ ② gọi ra (CHẬM, FAIL được)
                                                  │ machine + ledger▼
                                          [reconcile worker] ◄──► [Bank / Payment Gateway]
                                          quét PENDING, query lại        (NGOÀI — mock)
                                          self-healing sau crash         có status-query API
```

---

## 2. Bảng quyết định (từ phiên Socratic — E1..E10)

| # | Quyết định | Lý do (đã tự suy ra) |
|---|---|---|
| E1 | `withdraw` là **state machine có vòng đời**, không phải sự kiện tức thời: `PENDING → SENT → SETTLED / FAILED / NEEDS_MANUAL_REVIEW`. | Rút tiền thực tế không tức thời — nó có một chặng *gọi ra ngoài, mất thời gian, chưa biết kết quả*. Tái dùng đúng pattern KYC state machine (SP2): "make illegal states unrepresentable", không nhảy bừa. |
| E2 | Thêm **escrow account** (ví tạm chờ rút) + phân biệt **available balance** (tiêu được) vs **total/ledger balance** (tổng có). | Phá giả định 1-tài-khoản. Tiền "đang đi" cần chỗ thứ ba để ở: đã rời available (chống double-spend) nhưng chưa rời total (chưa mất). |
| E3 | Số dư bị trừ khỏi available **ngay tại bước ① (move vào escrow)**, KHÔNG đợi SETTLED. | Nếu đợi SETTLED thì lúc PENDING tiền còn available → user rút lần nữa = double-spend (phương án (b) sai). Move vào escrow = vẫn trong total nên không "nói dối" rằng tiền đã mất. |
| E4 | Tiền **không bị "trừ"** — nó **di chuyển giữa các tài khoản nội bộ** (ví→escrow→ra-ngoài / escrow→ví). Sổ cái ghi **double-entry** mỗi chặng. | Double-entry bookkeeping: tiền không tự sinh/biến mất, tổng `available + escrow + đã-ra-ngoài` là **bất biến**. "Mất mát số" trở nên *bất khả về cấu trúc*, không phải nhờ "retry cho cẩn thận". |
| E5 | 3 thao tác: ① ví→escrow (nội bộ, atomic), ② gọi bank (NGOÀI, rủi ro), ③ escrow→ra-ngoài/refund (nội bộ, atomic). Escrow **nhốt rủi ro** vào đúng ②. | Suốt vùng rủi ro ②, tiền nằm an toàn trong escrow — dù ② mất 10 phút, fail, hay service crash, sổ cái vẫn khớp. Biến thao tác rủi-ro-không-nguyên-tử thành [an toàn]+[rủi ro nhưng tiền đã cất kỹ]+[an toàn]. |
| E6 | Trạng thái `PENDING/SENT` lưu **bền vững trong DB** (không RAM). Một **reconciliation worker** quét định kỳ mọi lệnh chưa-terminal và lái tiếp tới đích. | Service crash sau khi ① commit nhưng trước khi gọi ② → lệnh gọi chỉ sống trong RAM tiến trình đã chết → tiền kẹt escrow vĩnh viễn. Trạng thái bền vững + worker = **self-healing**, sống lại đọc tiếp. |
| E7 | Mỗi lệnh rút mang **idempotency key (bank reference) sinh ở bước ①, ghi CÙNG MỘT record/transaction với tiền vào escrow**. Mọi retry (kể cả worker sau crash) dùng **LẠI** key cũ, không bao giờ sinh mới. | Atomic: hoặc cả tiền-trong-escrow lẫn key cùng tồn tại, hoặc cả hai cùng không — không có nửa vời. Nhờ vậy worker sau crash luôn gọi bank với cùng reference → bank nhận ra **dup** → không trả kép (chống mất 200k cho lệnh 100k). |
| E8 | Worker **không tin trí nhớ của mình — nó HỎI ngân hàng** qua **status-query API** (cho reference → trạng thái). | Sau crash, *bản thân ví không biết* ② đã tới bank hay chưa — chỉ ngân hàng biết. Phải query để có sự thật, không đoán. |
| E9 | Phân loại kết quả: **"từ chối dứt khoát"** (số TK sai → FAILED → refund an toàn) vs **"timeout/không trả lời" = UNKNOWN** (TUYỆT ĐỐI không refund, chỉ query lại). **"Không trả lời" ≠ "thất bại".** | Nếu bank đã chuyển tiền thật nhưng gói xác nhận bị mất (=timeout) mà ta coi là fail → refund → user nhận tiền ở bank + được hoàn trong ví = **mất trắng**. Chỉ được hành động khi có câu trả lời **DỨT KHOÁT**. |
| E10 | Hết ngưỡng retry (N lần / T giờ) vẫn UNKNOWN → trạng thái **`NEEDS_MANUAL_REVIEW`** (in-doubt transaction), đẩy vào **manual reconciliation queue (dead-letter)** cho con người quyết. | Thứ gì đoán sai làm **mất tiền thật** thì máy **không được tự quyết** — không refund (có thể đã đi), không settle (có thể chưa đi). Có DLQ = không bao giờ mất dấu một giao dịch nào; ops xử rồi quyết cuối. |

---

## 3. Mô hình tài khoản & số dư (E2, E3, E4)

### 3.1 Hai con số, không phải một

```
total_balance     = tổng tiền user còn sở hữu trong hệ (gồm cả đang chờ rút)
held / escrow      = phần đang bị giữ cho các lệnh rút PENDING/SENT
available_balance  = total_balance − held         ◄─ số mà lệnh rút MỚI phải soi
```

- Lệnh rút mới **chỉ được phép** nếu `amount ≤ available_balance` (E3 — đây là điều giết double-spend của phương án (b)).
- `held` tăng khi vào escrow, giảm khi SETTLED (ra ngoài) hoặc FAILED (refund về available).

### 3.2 Bất biến hệ thống (system invariant — để viết test & đối soát)

```
Tại MỌI thời điểm, với mỗi user:
  available + held + (đã ra ngoài/settled) = tổng tiền từng nạp − tổng đã settled hợp lệ
  => không dòng nào "bốc hơi"; mọi biến động là một CẶP bút toán double-entry.
```

---

## 4. State machine của withdraw (E1, E9, E10)

```
                       ┌──────────────────────────────────────────────┐
   [tạo lệnh rút]      │                                              │
        │             │        ② gọi bank                            │
        ▼             ▼                                               │
   ┌─────────┐   ┌────────┐  bank "OK" (DỨT KHOÁT)   ┌──────────┐    │
   │ PENDING ├──►│  SENT  ├─────────────────────────►│ SETTLED  │    │ ③ escrow→ra ngoài
   └────┬────┘   └───┬─┬──┘                          └──────────┘    │
   ① ví→escrow       │ │  bank "TỪ CHỐI" (DỨT KHOÁT)  ┌──────────┐    │
   (atomic, +key)    │ └────────────────────────────►│  FAILED  │◄───┘ ③ refund escrow→ví
                     │     timeout/UNKNOWN            └──────────┘
                     │  (worker query LẠI, cùng key)
                     │                                ┌─────────────────────┐
                     └───── hết N lần / T giờ ───────►│ NEEDS_MANUAL_REVIEW │──► (admin) → SETTLED | FAILED
                              vẫn UNKNOWN             └─────────────────────┘
```

| Trạng thái | Ý nghĩa | Tiền đang ở | Hành động hợp lệ |
|---|---|---|---|
| `PENDING` | đã ghi nhận, tiền đã vào escrow, **chưa** gọi bank | escrow | gọi bank (②) |
| `SENT` | đã gọi bank, **chưa** có kết quả dứt khoát | escrow | query bank lại |
| `SETTLED` | bank xác nhận **đã** chuyển → terminal ✅ | đã ra ngoài | — |
| `FAILED` | bank từ chối dứt khoát → đã refund về ví → terminal ✅ | available | — |
| `NEEDS_MANUAL_REVIEW` | in-doubt: hết retry vẫn UNKNOWN | escrow (đóng băng) | **con người** quyết |

> Mọi mũi tên KHÔNG vẽ ở trên đều là transition bất hợp lệ → chặn trong domain (như `InvalidKycTransitionException` của SP2). Ví dụ: `SETTLED → FAILED` (đã ra ngoài rồi không tự refund được), `PENDING → SETTLED` (chưa gọi bank sao biết settled).

---

## 5. Luồng đầy đủ — 3 thao tác & ranh giới (E5, E6)

> Nhắc lại luật vàng SP3 (D4): **không gọi remote bên trong DB transaction.** Nên ① commit XONG rồi mới gọi ②; ② xong mới mở tx ③. Ba mảnh rời nhau trên dòng thời gian — chính khe hở giữa chúng là lý do cần worker (E6).

```
POST /wallets/7/withdraw (X-User-Id=A, Idempotency-Key=k1, amount 100)
   │
   ▼ [0] Idempotency replay (SP3): k1 đã có -> trả lệnh cũ, DỪNG
   ▼ [1] Scoped load + AuthZ (SP3): ví 7 thuộc A? không -> 404 + audit
   ▼ [2] Cổng KYC (SP3, NGOÀI tx): APPROVED? không -> 403 / 503
   ▼ ─────────────────────────────────────────────────────────────
   ▼ ① TRANSACTION nội bộ (atomic):
   │     - available -= 100 ; held += 100  (move ví→escrow)
   │     - tạo WithdrawalOrder { state=PENDING, bankRef=<sinh ở đây>, amount, userId }
   │     - ghi ledger double-entry: (ví -100) + (escrow +100)
   │   commit  ─────────────────────────────────────────────  ◄── từ đây tiền AN TOÀN trong escrow
   │     - trả 202 Accepted { orderId, state: PENDING }   ← KHÔNG phải 200 "đã xong"
   │
   ▼ ② Gọi bank (NGOÀI tx, qua breaker + timeout):
   │     POST bank/transfer { bankRef, amount, dest }   state -> SENT
   │       bank "OK"        -> (sang ③ settle)
   │       bank "TỪ CHỐI"   -> (sang ③ refund)
   │       timeout/UNKNOWN  -> để nguyên SENT, worker xử sau (KHÔNG đoán)
   │
   ▼ ③ TRANSACTION nội bộ (atomic):
         settle: held -= 100 ; (đã ra ngoài += 100) ; state -> SETTLED ; ledger (escrow -100)+(ra ngoài +100)
         refund: held -= 100 ; available += 100      ; state -> FAILED  ; ledger (escrow -100)+(ví +100)
```

### ⚠️ Đổi hợp đồng API: `200 OK` → `202 Accepted`

`200` ngầm nói "đã xong" — nói dối user (E của phiên brainstorm). `202 Accepted` nói đúng sự thật: *"đã ghi nhận yêu cầu, đang xử lý"*, kèm `orderId` để user/poll tra trạng thái. Đây là cùng tinh thần webhook KYC: trả mã đúng ngữ nghĩa.

---

## 6. Reconciliation Worker (E6, E8) — trái tim self-healing

```
worker quét định kỳ (vd mỗi 30s) MỌI WithdrawalOrder ở trạng thái chưa-terminal:

  PENDING  (① commit nhưng ② chưa chạy — service từng crash ở khe này):
      -> hỏi bank status(bankRef):
           "chưa thấy lệnh"  -> gọi bank chuyển (②) bằng CÙNG bankRef
           "đã settle"       -> sang ③ settle (đường happy)
           "đã từ chối"      -> sang ③ refund

  SENT  (đã gọi bank, chưa có kết quả dứt khoát):
      -> hỏi bank status(bankRef):
           "đã settle"   -> ③ settle
           "đã từ chối"  -> ③ refund
           "chưa xong / không trả lời" = UNKNOWN -> bỏ qua vòng này, thử lại vòng sau
                                                    (đếm số lần; quá N/T -> NEEDS_MANUAL_REVIEW)
```

- **Trạng thái bền vững trong DB chính là hàng-đợi-việc-cần-làm** — không cần message queue riêng cho việc này; bảng `withdrawal_order` đã là nguồn sự thật.
- Worker **idempotent**: mọi bước ③ phải an toàn khi chạy lại (xem §8).
- **Khoá tranh chấp:** nhiều instance worker cùng quét → dùng `SELECT ... FOR UPDATE SKIP LOCKED` (hoặc optimistic @Version) để hai worker không xử cùng một order.

### Fast path + Slow path (bổ trợ nhau)

| Đường | Cơ chế | Vai trò |
|---|---|---|
| **Fast path** | Ngân hàng gọi **webhook** báo kết quả (như webhook verifier của KYC) | nhanh, gần real-time khi mọi thứ ổn |
| **Slow path** | Worker **poll** + query status định kỳ | **lưới an toàn** — webhook có thể mất, đến trễ, hoặc bank không hỗ trợ |

Webhook là **tối ưu**; worker là **bảo đảm**. Có cả hai = belt-and-suspenders. Webhook cũng phải idempotent (đến 2 lần / trùng → 200, no-op nếu order đã terminal).

---

## 7. Idempotency đa tầng (E7)

Một lệnh rút có **HAI** key idempotency ở **HAI biên** khác nhau — mỗi biên retry vì lý do riêng nên cần key riêng:

| Biên | Key | Sinh ở đâu | Chống cái gì |
|---|---|---|---|
| User → wallet | `Idempotency-Key` (SP2) | client sinh | user/client bấm rút 2 lần |
| wallet → bank | `bankRef` (mới) | **bước ①, cùng record với escrow, atomic** | wallet/worker gọi bank lại sau crash → bank dedup, không trả kép |

> Nguyên lý phổ quát: **mỗi biên có thể retry độc lập thì cần một mã idempotency RIÊNG.** Một mã không gánh được cả hai.

---

## 8. Mô hình dữ liệu (đề xuất)

```
withdrawal_order  (bảng MỚI — nguồn sự thật vòng đời rút)
  id              PK
  user_id         NOT NULL            ◄─ scoped AuthZ (SP3)
  wallet_id       NOT NULL
  amount          NOT NULL
  state           ENUM(PENDING,SENT,SETTLED,FAILED,NEEDS_MANUAL_REVIEW)
  bank_ref        NOT NULL UNIQUE     ◄─ E7: idempotency key tới bank, sinh ở ①
  idempotency_key (= Idempotency-Key của user, UNIQUE)  ◄─ SP2
  attempt_count   INT  DEFAULT 0      ◄─ E10: ngưỡng N
  first_sent_at   TIMESTAMP NULL      ◄─ E10: ngưỡng T
  version         (@Version, optimistic lock — chống 2 worker)
  created_at / updated_at

  -- số dư: KHÔNG lưu "balance" rời. available/held suy từ ledger + order đang mở,
  --        HOẶC giữ balance cache có held riêng (quyết định khi viết plan).
```

Mọi chuyển trạng thái = một (hoặc cặp) bút toán **append-only** trong `wallet_transaction` (ledger bất biến của Stage 2). Không UPDATE số tiền cũ — chỉ thêm dòng mới (đảo ngược nếu refund).

---

## 9. Hợp đồng lỗi & mã trạng thái (bổ sung cho SP1–SP3)

| Tình huống | HTTP | Ghi chú |
|---|---|---|
| Rút hợp lệ, đã ghi nhận | **202 Accepted** `{orderId, state:PENDING}` | E1 — không còn 200 "đã xong" |
| Không đủ **available** (đã trừ held) | 422 | E3 — soi available, KHÔNG soi total |
| `GET /wallets/{id}/withdrawals/{orderId}` | 200 `{state, ...}` | để client poll trạng thái |
| (kế thừa SP3) ví sai chủ | 404 + audit | D3 |
| (kế thừa SP3) KYC ≠ APPROVED | 403 | D7 |
| (kế thừa SP3) KYC không kiểm được | 503 + Retry-After | D7 |

---

## 10. Nợ kỹ thuật & YAGNI

**Nợ ghi nhận (có chủ đích):**
- Ngân hàng thật → SP4 dùng **mock adapter** (`BankClient` port + `MockBankClient`/MockWebServer). Hợp đồng: `transfer(bankRef, amount, dest)` + `status(bankRef)` + webhook callback.
- Kế thừa nợ SP3: `X-User-Id` chưa verify HMAC (wallet Stage 4); `shared-hmac` lib; transactional outbox; Redis thay Caffeine.
- **Observability nấc 2 (OTel):** hiện chỉ có `X-Trace-Id` thủ công (correlation ID). Khi cần soi độ trễ từng chặng (đặc biệt cú gọi bank ②) → gắn OpenTelemetry (span + duration + tags an toàn). Nền `X-Trace-Id` đã đặt sẵn nên nâng cấp nhanh. *(Brainstorm 2026-06-14.)*

**YAGNI (cố tình không làm ở SP4):**
- UI/console cho admin xử lý `NEEDS_MANUAL_REVIEW` — SP4 chỉ cần API + log; ops thao tác qua endpoint.
- Backoff phức tạp (exponential + jitter) — bắt đầu bằng poll cố định + đếm attempt.
- Phí giao dịch, hạn mức rút, đa tiền tệ.
- Tách `withdrawal` thành microservice riêng — giữ trong wallet-service.

---

## 11. Ma trận tình huống (để viết test)

| Kịch bản | Kỳ vọng |
|---|---|
| rút ≤ available, bank OK ngay | 202 → worker/webhook → SETTLED; available giảm, held về 0 |
| rút > available (do held từ lệnh trước) | 422 ngay; held không đổi |
| ① commit rồi **crash trước ②** | worker thấy PENDING → query bank "chưa thấy" → gọi bank cùng bankRef → SETTLED |
| ② gọi bank rồi **crash sau khi bank đã nhận** | worker thấy SENT → query "đã settle" → ③ settle (KHÔNG gọi lại transfer) |
| bank **từ chối** (số TK sai) | SENT → FAILED → refund; available phục hồi |
| bank **timeout** một lần | giữ SENT; worker query lại; nếu sau đó "settle" → SETTLED (KHÔNG refund) |
| bank **timeout mãi** (> N lần / T giờ) | → NEEDS_MANUAL_REVIEW; tiền vẫn trong escrow (đóng băng); KHÔNG auto refund/settle |
| webhook báo SETTLED **2 lần** | lần 2 no-op, 200 (idempotent) |
| 2 worker quét cùng một order | chỉ một xử lý (SKIP LOCKED / @Version) |
| double-spend: rút 2 lần cùng available | lần 2 thấy available đã giảm (held) → 422 |

---

## 12. Lộ trình triển khai đề xuất (TDD)

1. **Mô hình số dư available/held + WithdrawalOrder domain** (state machine + transition guard, như KycCase). Unit test ma trận transition.
2. **Đổi withdraw → tạo order PENDING + move escrow (① atomic) + 202.** available/held + ledger double-entry. (Bank chưa gọi — fake "luôn PENDING".)
3. **`BankClient` port + MockBankClient** (`transfer` + `status`) + bước ②③ settle/refund đường happy + breaker/timeout (tái dùng Resilience4j SP3).
4. **Reconciliation worker**: quét PENDING/SENT, query bank, drive tới terminal; SKIP LOCKED; idempotent ③. Test crash-recovery (giả lập order kẹt).
5. **Phân loại UNKNOWN vs FAILED + ngưỡng N/T → NEEDS_MANUAL_REVIEW** + endpoint admin resolve. Test "timeout không refund".
6. **Fast path webhook** (bank → wallet) idempotent + endpoint poll trạng thái cho user.
7. **Integration + e2e thật** (gateway + wallet + mock bank + Kafka): ma trận §11, gồm kịch bản crash & in-doubt.
