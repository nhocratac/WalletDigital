# E2E thật — 3 service + Kafka (SP3 KYC gate)

Chạy thật xuyên cả `api-gateway` + `wallet-service` + `kyc-service` + Kafka, kiểm chuỗi:
**submit KYC → approve (webhook) → tạo ví → topup → withdraw OK → revoke → withdraw 403**.

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
  WALLET_KYC_BASE_URL=http://localhost:8082 KAFKA_BOOTSTRAP=localhost:9092 mvn spring-boot:run

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
- Qua gateway (JWT): tạo ví, topup, withdraw.
- Trực tiếp tới kyc: submit (userId trong body), webhook approve (verifier ngoài),
  revoke (compliance + X-Roles) — đúng như các kênh đặc biệt ngoài đời.

Kết quả mong đợi: **7 PASS / 0 FAIL**.
