# Thiết kế: Wallet Stage 4 — Internal HMAC Auth (+ shared-hmac)

- **Ngày:** 2026-06-21
- **Phạm vi:** Bịt lỗ hổng zero-trust: `wallet-service` **verify HMAC inbound** (hiện đang TIN `X-User-Id`/`X-Tenant-Id` trần). Gồm: thêm `HmacVerifyFilter` ở wallet (soi gương `InternalAuthFilter` của kyc), **ký identity headers vào canonical** (sửa cả gateway), allowlist multi-caller, và **gom canonical/HMAC thành shared-hmac** (A3 — xóa trùng lặp 4 nơi).
- **Tiền đề:** SP1–SP7 (gateway ký HMAC khi gọi wallet; kyc đã có InternalAuthFilter; wallet có TenantFilter + bank-webhook secret riêng). Nợ ghi từ SP3.
- **Mục tiêu học:** zero-trust hoàn chỉnh, HMAC authenticity + integrity, ký identity vào canonical (chống tráo header), secret segmentation + allowlist, filter ordering (authenticate-before-use), replay protection, xóa nợ trùng-lặp.

> **Nguồn gốc:** quyết định S1–S7 do **CHÍNH NGƯỜI HỌC suy ra** (Socratic). Diagram render ở `.html` cùng tên (ASCII trong MD cho terminal).

---

## 1. Lỗ hổng (verify trên code 2026-06-21)

```
Gateway ──ký X-Signature + X-User-Id + X-Tenant-Id──► Wallet
   (gateway verify JWT, bóc claim, KÝ cẩn thận)        (TenantFilter chỉ ĐỌC X-Tenant-Id;
                                                         KHÔNG verify chữ ký → coi chữ ký như trang trí)
```
Toàn bộ kiểm soát truy cập của wallet dựa vào `X-User-Id` (D2 scoped query) + `X-Tenant-Id` (SP5 routing) **là THẬT**. Nhưng wallet không verify → kẻ gọi thẳng :8080, bỏ qua gateway, tự đặt header:
```
POST /wallets/99/withdraw   X-User-Id: nan-nhan   X-Tenant-Id: cong-ty-khac   {amount: 1tỷ}
```
→ **đụng ví bất kỳ ai + thấy dữ liệu tenant bất kỳ** → mất tiền + lộ chéo tenant. Đây là lỗ hổng zero-trust Stage 4 bịt.

---

## 2. Bảng quyết định (S1–S7 từ Socratic)

| # | Quyết định | Lý do (đã tự suy ra) |
|---|---|---|
| S1 | Wallet thêm **`HmacVerifyFilter` inbound** (soi gương `InternalAuthFilter` của kyc), chạy **TRƯỚC `TenantFilter`**. | Một chữ ký hợp lệ chứng minh **origin** (chỉ entity biết secret mới ký được = gateway) + **integrity** (canonical phủ method/path/body). Verify phải đứng TRƯỚC TenantFilter vì TenantFilter *routing theo `X-Tenant-Id`* — phải xác thực header xong **mới được dùng** (authenticate-before-use). |
| S2 | **Ký `X-User-Id` + `X-Tenant-Id` VÀO canonical** (sửa `buildCanonical` ở gateway; wallet verify gồm chúng). | Canonical hiện = `serviceId\nmethod\npath\nts\nhash(body)` — KHÔNG có identity → verify chứng minh origin nhưng **không ràng buộc danh tính**: kẻ chen giữa đổi `X-User-Id` mà chữ ký vẫn đúng → vẫn mạo danh. Ký identity vào → đổi header = chữ ký vỡ. Stage 4 vì thế là thay đổi **phối hợp gateway + wallet**. |
| S3 | **Multi-caller bằng `X-Service-Id` + allowlist** → tra **secret theo từng service** (gateway-secret vs bank-webhook-secret). | Không phải mọi request từ gateway: webhook bank gọi thẳng, ký **secret RIÊNG** (segmentation). Filter phải biết caller để chọn đúng secret + đúng kỳ vọng canonical. kyc đã làm vậy (allowlist `kyc.allowed-services`). |
| S4 | **Đường webhook bank được MIỄN** identity header — verify bằng secret-bank + canonical-không-identity; tự khôi phục tenant từ `bankRef`. | Bank không biết tenant/user của hệ ta → không thể mang `X-User-Id`/`X-Tenant-Id`. Bắt buộc identity cho MỌI request là sai. Webhook xác thực bằng chính secret riêng của nó (đã có `wallet.bank.webhook-secret`). |
| S5 | **Replay protection:** canonical có `timestamp`; filter từ chối request quá cũ (cửa sổ vài phút). | Chữ ký đúng nhưng cũ → có thể là replay. Timestamp + freshness window thu hẹp cửa sổ. (kyc đã có.) |
| S6 | **shared-hmac:** gom builder-canonical + sign/verify vào MỘT chỗ dùng chung (gateway ký, wallet verify + ký outbound, kyc verify, RestKycGate/RestBankClient ký). | Canonical đang lặp ở **≥4 nơi** → đổi (thêm identity) mà rải rác = dễ lệch → bug auth. Một builder chung (nhận field, canonical content tùy hop) = đổi một nơi, nhất quán. |
| S7 | **Hợp đồng lỗi:** thiếu/sai chữ ký → **401**; timestamp quá cũ → 401; `X-Service-Id` ngoài allowlist → 401/403. | Không lộ chi tiết vì sao (tránh giúp kẻ dò); log nội bộ đầy đủ (như D3). |

---

## 3. Chuỗi filter wallet (sau Stage 4)

```
request đi vào wallet
   │
   ▼ [1] HmacVerifyFilter  (ƯU TIÊN CAO NHẤT — S1)
   │     - đọc X-Service-Id → allowlist? không → 401          (S3)
   │     - tra secret theo service-id (gateway / bank-webhook)
   │     - dựng canonical KỲ VỌNG theo caller:
   │         gateway     : serviceId\nmethod\npath\nts\nhash(body)\nX-User-Id\nX-Tenant-Id   (S2)
   │         bank-webhook: serviceId\nmethod\npath\nts\nhash(body)        (S4 — không identity)
   │     - so chữ ký + check timestamp freshness                (S5)
   │     - sai → 401 (+ audit log)                              (S7)
   ▼ [2] TenantFilter  (giờ X-Tenant-Id ĐÃ được xác thực → routing an toàn)  (SP5)
   ▼ [3] controller → service
```

> Bank-webhook path: `HmacVerifyFilter` nhận diện qua `X-Service-Id` (hoặc path `/webhooks/bank/**`), verify bằng secret-bank, **bỏ qua** yêu cầu identity; `TenantFilter` vốn đã skip path này (SP4) — tenant khôi phục từ `bankRef`.

---

## 4. shared-hmac (A3) — gom canonical/HMAC

```
TRƯỚC: canonical + HMAC lặp ở: gateway(RequestSigner) · kyc(InternalAuthFilter) ·
       wallet RestKycGate(ký) · wallet RestBankClient(ký)  → ≥4 bản, dễ lệch khi đổi S2

SAU:  module/lib shared-hmac:
        Hmac.sign(secret, canonical)  /  Hmac.verify(secret, canonical, sig)
        Canonical.build(serviceId, method, path, ts, bodyHash, [identityHeaders...])  ← field tùy hop
      dùng bởi: gateway(ký) · wallet HmacVerifyFilter(verify) + Rest*Client(ký outbound) · kyc(verify)
```
- **Nội dung canonical KHÁC nhau theo hop** (gateway→wallet có identity; bank-webhook không; kyc hop riêng) → shared là **cơ chế** (HMAC + hash + builder nhận field), KHÔNG phải một canonical cứng.
- ⚠️ **Ripple:** thêm identity vào canonical gateway→wallet → nếu builder dùng chung cho **gateway→kyc**, kyc cũng phải verify canonical mới → **thay đổi hợp đồng phối hợp** (deploy gateway + wallet + kyc đồng bộ, hoặc version canonical). Ghi nhận; phạm vi Stage 4 ưu tiên hop gateway→wallet.

---

## 5. Trust boundary (hoàn tất zero-trust)

```
TRƯỚC: gateway xác thực USER (JWT). wallet TIN gateway mù quáng (header trần).
SAU:   gateway xác thực USER (JWT) → ký.  wallet xác thực CALLER (HMAC) + ràng buộc identity.
       Mỗi service tự xác thực caller của nó — không service nào tin header trần.
```
→ kyc đã ở trạng thái này (InternalAuthFilter); Stage 4 đưa wallet ngang hàng → **toàn hệ zero-trust nhất quán**.

---

## 6. Nợ kỹ thuật & YAGNI
- **Ripple canonical sang kyc** (S6) — quyết định version canonical hay deploy đồng bộ khi triển khai.
- mTLS thay/bổ sung HMAC (mạnh hơn, nặng hạ tầng) — YAGNI; HMAC đủ cho biên nội bộ học tập.
- Xoay (rotate) HMAC secret — ghi nhận, chưa làm.
- Replay store (nonce) chống replay tuyệt đối — hiện chỉ timestamp-window (đủ cho scope); nâng nếu cần.

---

## 7. Chiến lược kiểm thử

```
Unit (HmacVerifyFilter):
  · chữ ký đúng (gateway, canonical-có-identity) → cho qua, set X-User-Id/X-Tenant-Id tin được
  · sai chữ ký → 401 ; thiếu chữ ký → 401
  · ĐỔI X-User-Id sau khi ký (canonical cũ không phủ) → PHẢI 401 (chứng minh S2 ràng buộc identity)
  · X-Service-Id ngoài allowlist → 401/403
  · timestamp quá cũ → 401 (S5)
  · path webhook bank: verify secret-bank, KHÔNG đòi identity → cho qua (S4)
Filter ordering:
  · HmacVerifyFilter chạy TRƯỚC TenantFilter (request giả X-Tenant-Id không qua được verify → TenantFilter không bao giờ routing nhầm)
Integration (gateway + wallet thật):
  · gateway ký (canonical mới có identity) → wallet verify OK → luồng cũ (withdraw/transfer/topup) nguyên vẹn
  · gọi thẳng wallet bỏ qua gateway, tự đặt X-User-Id → 401 (lỗ hổng đã bịt)
  · webhook bank ký secret-bank → settle/refund vẫn chạy
Regression: toàn bộ test SP1–SP7 xanh (sau khi cập nhật test ký canonical mới — drift có chủ đích).
```

---

## 8. Lộ trình triển khai đề xuất (TDD)
1. **shared-hmac** (S6): trích `Canonical.build(...)` + `Hmac.sign/verify` (module/package dùng chung); gateway + kyc + Rest*Client chuyển sang dùng — **giữ canonical cũ**, test xanh (refactor thuần).
2. **Thêm identity vào canonical** (S2): `buildCanonical` nhận `X-User-Id`/`X-Tenant-Id` cho hop gateway→wallet; gateway ký gồm chúng. (chưa có verify ở wallet → tạm thời wallet vẫn bỏ qua.)
3. **`HmacVerifyFilter` ở wallet** (S1,S3,S5,S7): allowlist X-Service-Id + secret theo service + verify canonical (gồm identity cho gateway) + timestamp window + 401. Đặt trước TenantFilter.
4. **Miễn webhook bank** (S4): nhận diện path/service → canonical-không-identity + secret-bank.
5. **Integration + e2e**: gọi-thẳng-bỏ-gateway → 401; luồng qua gateway nguyên vẹn; webhook bank chạy.
