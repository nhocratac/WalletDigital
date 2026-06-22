# E2E thật — 3 service + Kafka + mock bank (SP3 KYC gate + SP4 settlement)

Chạy thật xuyên cả `api-gateway` + `wallet-service` + `kyc-service` + Kafka + mock bank, kiểm chuỗi:
**submit KYC → approve (webhook) → tạo ví → topup → withdraw (202) → poll SETTLED → withdraw reject → poll FAILED (refund) → revoke → withdraw 403**.

> SP4: `withdraw` giờ là vòng đời bất đồng bộ — trả **202 Accepted** + `orderId`; reconciliation
> worker (slow path) lái order tới terminal qua MockBankClient. e2e poll `GET .../withdrawals/{orderId}`
> tới SETTLED/FAILED (Awaitility kiểu bash: vòng lặp curl + sleep), rồi kiểm `balance` (total).

> Loại test này bắt được bug mà unit/integration test (dùng mock) bỏ lọt — đã từng tóm
> 2 bug gateway nuốt header (`Content-Type`, `Idempotency-Key`) và 1 gap `X-User-Id`.

## Cách chạy

```bash
# 1. Kafka
docker compose up -d            # đợi ~15-20s (KRaft)

# 2. Sinh khoá RSA + ký JWT user (sub=user-1, tenantId=acme)
source e2e/lib.sh && gen_keys_and_token

# 3. Bật 3 service (mỗi cái 1 terminal), secret nội bộ đặt CHUNG = e2e-internal:
cd kyc-service && KYC_KAFKA_ENABLED=true KYC_INTERNAL_HMAC_SECRET=e2e-internal \
  KYC_VERIFIER_HMAC_SECRET=e2e-verifier KAFKA_BOOTSTRAP=localhost:9092 mvn spring-boot:run

cd wallet-service && WALLET_KAFKA_ENABLED=true WALLET_KYC_HMAC_SECRET=e2e-internal \
  WALLET_INTERNAL_HMAC_SECRET=e2e-internal \
  WALLET_KYC_BASE_URL=http://localhost:8082 KAFKA_BOOTSTRAP=localhost:9092 \
  WALLET_BANK_MOCK=true WALLET_RECONCILE_ENABLED=true WALLET_RECONCILE_INTERVAL_MS=1000 \
  mvn spring-boot:run
# Stage4: WALLET_INTERNAL_HMAC_SECRET PHẢI khớp GATEWAY_HMAC_SECRET (=e2e-internal) — wallet verify
# HMAC inbound (auth-enabled mặc định true). Gọi thẳng wallet không chữ ký hợp lệ -> 401 (kịch bản [8]/[9]).

cd api-gateway && GATEWAY_HMAC_SECRET=e2e-internal \
  GATEWAY_JWT_PUBLIC_KEY="$(cat /tmp/e2e_sp3/pub.b64)" mvn spring-boot:run

# 4. Chạy kịch bản (in PASS/FAIL từng bước)
bash e2e/scenario.sh

# 5. Dọn
docker compose down
# nhớ kill tiến trình GIỮ CỔNG 8080/8081/8082 (không chỉ pkill maven — bài học orphan process)
```

## Chuỗi secret (vì sao đặt chung e2e-internal)
- gateway ký HMAC gọi wallet → wallet KHÔNG verify (nợ Stage 4) nên bỏ qua.
- wallet ký HMAC gọi kyc → kyc verify bằng `kyc.internal-hmac-secret` → phải khớp.
- gọi trực tiếp kyc (submit/revoke) ký serviceId=`api-gateway` (trong allowlist).
- webhook ký bằng `e2e-verifier` (secret RIÊNG — secret segmentation).

## Luồng gọi
- Qua gateway (JWT): tạo ví, topup, withdraw, poll trạng thái order.
- Trực tiếp tới kyc: submit (userId trong body), webhook approve (verifier ngoài),
  revoke (compliance + X-Roles) — đúng như các kênh đặc biệt ngoài đời.
- Trực tiếp tới wallet `/mock-bank/default?result=...` (chỉ bật khi `wallet.bank.mock=true`): vòi
  điều khiển MockBankClient để e2e dựng kịch bản SETTLED/REJECTED — KHÔNG phải API nghiệp vụ.

Kết quả mong đợi: **12 PASS / 0 FAIL** (submit + webhook + create + topup + 2 withdraw-202 + 2 poll-terminal + 2 balance + revoke + withdraw-403).

## SP5 — E2E đa-tenant cách ly (`scenario-tenant.sh`)

Chứng minh **schema-per-tenant**: hai tenant (`acme`, `globex`) cách ly hoàn toàn qua gateway.
Onboard cả hai (`POST /admin/tenants` thẳng vào wallet, role `ops`) → tạo ví + topup ở `acme` bằng
JWT acme → dùng JWT globex truy cập ví của acme → **404** (globex không thấy schema acme) → globex
tạo ví độc lập, balance riêng. Lộ-chéo tenant là bất khả thi vì routing đổi schema lúc mở connection.

> **Yêu cầu MySQL thật** (schema-per-tenant + Flyway + master registry — H2 in-mem không hợp). Chạy
> wallet với `WALLET_DB_URL`/`WALLET_DB_USERNAME`/`WALLET_DB_PASSWORD` trỏ MySQL (user có quyền
> `CREATE SCHEMA` để onboarding dựng `tenant_acme`/`tenant_globex`). Onboarding tự `CREATE SCHEMA` +
> `flyway.migrate(db/migration/tenant)` → ACTIVE.

```bash
# 1. Kafka + MySQL (docker compose) — đợi MySQL sẵn sàng
docker compose up -d

# 2. Sinh khoá RSA + JWT user mặc định, RỒI hai JWT tenant (acme, globex)
source e2e/lib.sh && gen_keys_and_token && gen_two_tenant_tokens

# 3. Bật 3 service như trên, NHƯNG wallet trỏ MySQL (user CREATE SCHEMA), ví dụ:
cd wallet-service && WALLET_DB_URL="jdbc:mysql://localhost:3306/" \
  WALLET_DB_USERNAME=root WALLET_DB_PASSWORD=secret \
  WALLET_DB_DRIVER=com.mysql.cj.jdbc.Driver \
  WALLET_KAFKA_ENABLED=true WALLET_KYC_HMAC_SECRET=e2e-internal \
  WALLET_INTERNAL_HMAC_SECRET=e2e-internal \
  WALLET_KYC_BASE_URL=http://localhost:8082 KAFKA_BOOTSTRAP=localhost:9092 \
  mvn spring-boot:run
# (kyc + gateway như mục "Cách chạy")

# 4. Chạy kịch bản đa-tenant (in PASS/FAIL từng bước)
bash e2e/scenario-tenant.sh

# 5. Dọn
docker compose down   # nhớ kill PID giữ cổng 8080/8081/8082
```

Kết quả mong đợi: **8 PASS / 0 FAIL** (onboard×2 + acme create + acme topup + globex-thấy-acme=404 +
globex create + globex topup + 2 balance độc lập). `scenario-tenant.sh` re-run được phần onboard
(201 lần đầu, 409 lần sau); ví/topup vẫn vướng `Idempotency-Key` toàn cục như SP3 → restart wallet
nếu muốn chạy lại sạch.

> ⚠️ Scenario KHÔNG re-run được trên cùng instance wallet: `Idempotency-Key` (`t1`/`w1`/...) là toàn cục theo đời sống wallet → chạy lần 2 đụng key cũ → 422 (đúng hành vi same-key-different-payload, Stage 2). Muốn chạy lại: **restart wallet** (H2 in-memory reset). Balance check dùng so-sánh-SỐ (`check_num`) vì API trả `70.00` còn kỳ vọng viết `70.0`.

## Observability — sợi chỉ traceId xuyên 3 service (`trace-thread.sh`)

Chứng minh **một traceId nối liền** gateway → wallet → kyc (OB1–OB4). Gọi `withdraw` qua gateway với
header `X-Trace-Id: e2e-trace-xyz`; một request withdraw chạm cả 3 service:

```
gateway (TraceIdFilter đọc X-Trace-Id → MDC, forward header xuống wallet)
  → wallet (TraceIdFilter → MDC; outbound RestKycGate interceptor đính X-Trace-Id → kyc)
    → kyc (TraceIdFilter đọc X-Trace-Id → MDC)
```

→ `grep "e2e-trace-xyz"` trong log CỦA CẢ 3 service đều ra (sợi chỉ liền). Gateway còn **echo**
`X-Trace-Id` trên response header (sợi chỉ quay về client).

Script **tự dựng stack** (3 service background, mỗi cái log ra `/tmp/e2e_trace_logs/<svc>.log`),
drive kịch bản, grep cả 3 log, rồi **dọn** (kill PID giữ cổng). Chỉ cần Kafka chạy sẵn:

```bash
docker compose up -d            # Kafka KRaft trên localhost:9092
bash e2e/trace-thread.sh        # chạy từ repo root
```

Kết quả mong đợi: **8 PASS / 0 FAIL** (submit + approve + create + topup + gateway-echo-traceId +
grep traceId ở gateway/wallet/kyc).

> Dùng `tenantId=default` (schema baseline H2 — không cần provision MySQL `tenant_*`). Bật DEBUG cho
> `org.springframework.web` lúc chạy để mỗi request log một dòng TRÊN servlet thread (sau khi
> TraceIdFilter đặt MDC) → dòng đó mang `[%X{traceId}]` — KHÔNG sửa code production, chỉ tăng log lúc e2e.
