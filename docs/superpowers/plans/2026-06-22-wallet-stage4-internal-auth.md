# Wallet Stage 4 — Internal HMAC Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development / executing-plans. Steps dùng checkbox (`- [ ]`).

**Goal:** Wallet **verify HMAC inbound** thay vì tin `X-User-Id`/`X-Tenant-Id` trần. Gồm: `HmacVerifyFilter` ở wallet (soi gương kyc `InternalAuthFilter`), **ký identity vào canonical** (sửa gateway + đồng bộ kyc), allowlist multi-caller (gateway vs bank-webhook), miễn webhook bank.

**Architecture:** Theo design `docs/superpowers/specs/2026-06-21-wallet-stage4-internal-auth-design.md` (S1–S7). Tái dùng pattern kyc `InternalAuthFilter`/`HmacVerifier`. Filter `@Order(0)` (trước `TenantFilter @Order(1)` — authenticate-before-use).

**Tech Stack:** Java 25, Spring Boot 3.4.4, OncePerRequestFilter, H2 + Testcontainers MySQL, Resilience4j.

**⚠️ LƯU Ý DRIFT:** gateway `RequestSigner` (domain port) ký mọi downstream cùng canonical; kyc `InternalAuthFilter`+`HmacVerifier` verify; wallet `RestKycGate`/`RestBankClient` ký outbound. Đổi canonical (S2) **lan tới hop gateway→kyc** → phải đồng bộ. Toàn bộ 316 test (wallet 230 + gateway 28 + kyc 58) phải xanh.

---

## Quyết định khoá (chốt ở plan)

1. **Canonical "identity-if-present"** (tinh chỉnh để TƯƠNG THÍCH NGƯỢC): `buildCanonical` **append `X-User-Id`/`X-Tenant-Id` KHI CÓ trên request** (thứ tự cố định). → cùng một builder cho mọi hop:
   - gateway→wallet: có identity → canonical gồm identity (gateway ký gồm, wallet verify gồm) ✓
   - direct→kyc (submit/revoke, không gateway) / webhook bank: KHÔNG có identity → canonical **không đổi** so với hiện tại → **kyc & các đường cũ không vỡ** ✓
   - An toàn: kẻ tấn công **thêm** X-User-Id giả → canonical đổi → chữ ký (không forge được) lệch → 401; **bỏ** X-User-Id → controller wallet vẫn 400 (đã đòi header). → không tráo được.
2. **Wallet secret inbound MỚI:** `wallet.internal.hmac-secret` (khớp `GATEWAY_HMAC_SECRET`) + `wallet.internal.allowed-services=api-gateway`. Webhook bank dùng `wallet.bank.webhook-secret` (đã có).
3. **`HmacVerifyFilter @Order(0)`** trước `TenantFilter @Order(1)`. Bỏ qua/định tuyến riêng path webhook bank.
4. **shared-hmac (S6) PHẠM VI:** plan này tạo `HmacVerifier` **trong wallet** (soi gương kyc) — chấp nhận tạm trùng. **Module shared-hmac dùng chung 3 service cần parent POM (build-infra) → tách thành effort riêng**, ghi nợ. (Tránh workflow chอกชะงัก vì đổi cấu trúc build.)

---

## Cấu trúc thay đổi

```
wallet-service/
├── src/main/java/com/vng/wallet/infrastructure/security/
│   ├── HmacVerifier.java        (Create: buildCanonical identity-if-present + verify + isTimestampFresh — mirror kyc)
│   └── HmacVerifyFilter.java    (Create: @Order(0), allowlist, secret-per-service, 401)
├── src/main/resources/application.properties  (+ wallet.internal.hmac-secret, allowed-services)
api-gateway/
├── .../RequestSigner impl + buildCanonical  (Modify: append X-User-Id/X-Tenant-Id if present)
kyc-service/
├── .../security/HmacVerifier.java           (Modify: buildCanonical identity-if-present — backward-compat)
```

---

## Task 1: Wallet `HmacVerifier` + config (canonical identity-if-present)

**Files:** Create `infrastructure/security/HmacVerifier.java`; Modify `application.properties`; (test) `HmacVerifierTest`.

- [ ] **Step 1: Test:** `buildCanonical(serviceId, method, path, ts, body, userId, tenantId)` — khi userId/tenantId null → canonical = `serviceId\nmethod\npath\nts\nsha256(body)` (giống cũ); khi có → append `\nuserId\ntenantId`. `verify(secret, canonical, sig)` đúng/sai. `isTimestampFresh(now, ts, 300)`.
- [ ] **Step 2:** `mvn -q test -Dtest=HmacVerifierTest` → FAIL.
- [ ] **Step 3:** Cài `HmacVerifier` (mirror kyc: HMAC-SHA256, sha256 hex body, canonical identity-if-present, timestamp skew). Config: `wallet.internal.hmac-secret=${WALLET_INTERNAL_HMAC_SECRET:...}`, `wallet.internal.allowed-services=api-gateway`.
- [ ] **Step 4:** `cd wallet-service && mvn -q test` → xanh (chỉ thêm class, chưa đụng luồng).
- [ ] **Step 5:** `git commit -m "feat(wallet): HmacVerifier (identity-if-present canonical) + internal auth config (Stage4 S2)"`

---

## Task 2: Canonical đồng bộ — gateway ký identity + kyc verifier cập nhật (S2, backward-compat)

**Files:** Modify gateway `RequestSigner` impl (`buildCanonical`) + chỗ gọi (truyền X-User-Id/X-Tenant-Id); Modify kyc `HmacVerifier.buildCanonical`; (test) gateway signer test + kyc filter test.

- [ ] **Step 1: Test:**
  - gateway: `buildCanonical` khi có userId/tenantId → append; ký gồm chúng. (request qua gateway tới wallet mang chữ ký phủ identity.)
  - kyc: `buildCanonical` identity-if-present → **direct call không identity → canonical KHÔNG đổi** → 58 test kyc cũ vẫn xanh (backward-compat).
- [ ] **Step 2:** `mvn -q test` (gateway + kyc) → FAIL.
- [ ] **Step 3:** gateway `RequestSigner.buildCanonical(...)` nhận identity, append-if-present; `GatewayService` truyền `caller.userId()/tenantId()` vào. kyc `HmacVerifier.buildCanonical` đổi sang append-if-present (đường direct không identity → không đổi).
- [ ] **Step 4:** `cd api-gateway && mvn -q test` + `cd kyc-service && mvn -q test` → xanh.
- [ ] **Step 5:** `git commit -m "feat(gateway,kyc): sign/verify identity-if-present canonical (Stage4 S2, backward-compatible)"`

---

## Task 3: `HmacVerifyFilter` ở wallet (S1, S3, S5, S7)

**Files:** Create `infrastructure/security/HmacVerifyFilter.java`; (test) `HmacVerifyFilterTest`.

- [ ] **Step 1: Test (ma trận):**
  - gateway ký đúng (canonical gồm identity) → qua, request tới controller với X-User-Id tin được.
  - sai chữ ký → 401; thiếu chữ ký/timestamp → 401.
  - ⭐ **ký xong ĐỔI `X-User-Id`** (canonical phủ identity) → 401 (chứng minh S2 ràng buộc).
  - `X-Service-Id` ngoài allowlist → 401/403.
  - timestamp quá cũ (>300s) → 401 (S5).
  - filter chạy **TRƯỚC** TenantFilter (request giả X-Tenant-Id chưa ký → 401, không bao giờ tới routing).
- [ ] **Step 2:** `mvn -q test -Dtest=HmacVerifyFilterTest` → FAIL.
- [ ] **Step 3:** `HmacVerifyFilter extends OncePerRequestFilter @Order(0)`: cache body (ContentCachingRequestWrapper); đọc X-Service-Id → allowlist (`wallet.internal.allowed-services`); tra secret (`wallet.internal.hmac-secret`); dựng canonical identity-if-present từ header request; verify + timestamp; sai → 401 + audit log (không lộ lý do — S7). (mirror kyc InternalAuthFilter.)
- [ ] **Step 4:** `cd wallet-service && mvn -q test` → xanh (cập nhật test cũ: request test giờ phải ký HMAC — drift có chủ đích; dùng test helper ký).
- [ ] **Step 5:** `git commit -m "feat(wallet): HmacVerifyFilter before TenantFilter — verify inbound HMAC + bind identity (S1,S3,S5,S7)"`

---

## Task 4: Miễn webhook bank (S4)

**Files:** Modify `HmacVerifyFilter` (nhận diện path/service webhook bank → secret-bank, canonical-không-identity); (test) bổ sung `HmacVerifyFilterTest`.

- [ ] **Step 1: Test:**
  - request tới `/webhooks/bank/**` (hoặc `X-Service-Id: bank`) → verify bằng `wallet.bank.webhook-secret`, canonical KHÔNG đòi identity → ký đúng thì qua.
  - webhook bank sai chữ ký → 401.
  - đường webhook KHÔNG bị đòi `X-User-Id` (S4).
- [ ] **Step 2:** `mvn -q test` → FAIL → cài (filter rẽ nhánh theo path/service-id: gateway→internal-secret+identity; bank→webhook-secret+no-identity) → PASS.
- [ ] **Step 3:** `git commit -m "feat(wallet): bank-webhook HMAC exemption — separate secret, no identity (S4)"`

---

## Task 5: Integration + e2e — lỗ hổng bịt, luồng cũ nguyên vẹn

**Files:** (test) `WalletInternalAuthIntegrationTest`; Modify `e2e/` (sign.sh / lib.sh: canonical gồm identity; bật `WALLET_INTERNAL_HMAC_SECRET`).

- [ ] **Step 1: Integration (gateway + wallet thật / @SpringBootTest):**
  - ⭐ gọi THẲNG wallet :8080 bỏ qua gateway, tự đặt `X-User-Id` (không chữ ký hợp lệ) → **401** (lỗ hổng đã bịt).
  - request qua gateway (ký canonical mới gồm identity) → verify OK → withdraw/transfer/topup nguyên vẹn.
  - webhook bank ký secret-bank → settle/refund chạy.
- [ ] **Step 2: e2e:** cập nhật `e2e/lib.sh`+`sign.sh` ký canonical **identity-if-present**; gateway tự ký (qua gateway thật) nên scenario qua gateway không cần đổi; thêm 1 ca: curl thẳng wallet với X-User-Id giả → 401. Bật `WALLET_INTERNAL_HMAC_SECRET=e2e-internal` (khớp `GATEWAY_HMAC_SECRET`).
- [ ] **Step 3:** chạy 3 service + MySQL + Kafka; xác nhận. Dọn (kill PID giữ cổng).
- [ ] **Step 4:** `git commit -m "test(stage4): direct-call bypass returns 401 + gateway flow intact + bank webhook ok"`

---

## Nợ kỹ thuật & YAGNI
- **shared-hmac module dùng chung 3 service** (S6) — cần parent POM/multi-module; tách effort riêng (giờ wallet có HmacVerifier riêng, chấp nhận trùng tạm với kyc).
- Ripple canonical sang hop gateway→kyc đã xử lý bằng "identity-if-present" (backward-compat) — không cần version canonical.
- Rotate secret / nonce-replay-store — ghi nợ; hiện timestamp-window đủ.
- mTLS — YAGNI.

## Checklist Done
- [ ] gọi thẳng wallet với X-User-Id giả → 401 (lỗ hổng zero-trust bịt).
- [ ] đổi X-User-Id sau khi ký → 401 (identity ràng buộc vào canonical).
- [ ] HmacVerifyFilter chạy TRƯỚC TenantFilter; allowlist X-Service-Id; timestamp window.
- [ ] webhook bank: secret riêng, không đòi identity, vẫn chạy.
- [ ] canonical identity-if-present → kyc + đường direct cũ KHÔNG vỡ (backward-compat).
- [ ] 316 test xanh (wallet/gateway/kyc) + test mới; git clean.
