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
  WALLET_KYC_BASE_URL=http://localhost:8082 KAFKA_BOOTSTRAP=localhost:9092 \
  WALLET_BANK_MOCK=true WALLET_RECONCILE_ENABLED=true WALLET_RECONCILE_INTERVAL_MS=1000 \
  mvn spring-boot:run

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

> ⚠️ Scenario KHÔNG re-run được trên cùng instance wallet: `Idempotency-Key` (`t1`/`w1`/...) là toàn cục theo đời sống wallet → chạy lần 2 đụng key cũ → 422 (đúng hành vi same-key-different-payload, Stage 2). Muốn chạy lại: **restart wallet** (H2 in-memory reset). Balance check dùng so-sánh-SỐ (`check_num`) vì API trả `70.00` còn kỳ vọng viết `70.0`.
