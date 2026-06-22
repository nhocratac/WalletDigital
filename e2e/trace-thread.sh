#!/bin/bash
# E2E Observability Task 6 (OB1-OB6): MỘT traceId nối liền sợi chỉ xuyên CẢ 3 service.
#
# Gọi withdraw qua gateway với header `X-Trace-Id: e2e-trace-xyz`. Một request withdraw
# chạm cả 3 service:
#   gateway  (TraceIdFilter đọc X-Trace-Id -> MDC, forward header xuống wallet)
#   wallet   (TraceIdFilter -> MDC; outbound RestKycGate interceptor đính X-Trace-Id -> kyc)
#   kyc      (TraceIdFilter đọc X-Trace-Id -> MDC)
# => grep "e2e-trace-xyz" trong log CỦA CẢ 3 service đều phải ra (sợi chỉ liền, OB1-OB4).
#
# Script tự dựng stack (3 service, mỗi cái log ra file riêng), drive kịch bản, grep, rồi DỌN
# (kill PID giữ cổng). Kafka phải đang chạy sẵn (docker compose up -d) trên localhost:9092.
#
# Dùng: bash e2e/trace-thread.sh   (chạy từ repo root)
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
D=/tmp/e2e_sp3
LOGDIR=/tmp/e2e_trace_logs
TRACE=e2e-trace-xyz
GW=http://localhost:8081
KYC=http://localhost:8082
mkdir -p "$D" "$LOGDIR"
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "  ✅ $1 -> $2"; pass=$((pass+1)); else echo "  ❌ $1 -> $2 (mong đợi $3)"; fail=$((fail+1)); fi; }
grep_trace() { # grep_trace <label> <logfile>
  if grep -q "$TRACE" "$2"; then echo "  ✅ $1: log mang traceId '$TRACE'"; pass=$((pass+1));
  else echo "  ❌ $1: KHÔNG thấy '$TRACE' trong $2"; fail=$((fail+1)); fi
}

PIDS=()
cleanup() {
  echo "=== Dọn: kill 3 service ==="
  for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done
  # phòng orphan giữ cổng (bài học README): kill theo cổng
  for port in 8080 8081 8082; do
    pid=$(lsof -tiTCP:$port -sTCP:LISTEN 2>/dev/null)
    [ -n "$pid" ] && kill $pid 2>/dev/null
  done
}
trap cleanup EXIT

# 1) khoá + JWT. Dùng tenantId=default -> wallet route schema baseline H2 (single-schema, không cần
# provision MySQL tenant_*). gen_keys_and_token sinh RSA key; gen_tenant_token ký JWT default.
source "$ROOT/e2e/lib.sh"
gen_keys_and_token
gen_tenant_token "user-1" "default" "$D/default.jwt"
cp "$ROOT/e2e/sign.sh" "$D/sign.sh"
JWT=$(cat "$D/default.jwt")
sign() { bash "$D/sign.sh" "$@"; }

# 2) Bật 3 service (background, log ra file). Secret nội bộ CHUNG = e2e-internal (xem README).
echo "=== Bật kyc-service (log: $LOGDIR/kyc.log) ==="
# LOGGING_LEVEL_...DispatcherServlet=DEBUG -> mỗi request log một dòng TRÊN servlet thread (SAU khi
# TraceIdFilter đặt MDC) => dòng đó mang [%X{traceId}]. Happy-path controller im lặng nên cần dòng này
# để chứng minh sợi chỉ (không phải sửa code production — chỉ bật log lúc chạy e2e).
DBG=org.springframework.web.servlet.DispatcherServlet
echo "=== Bật kyc-service (log: $LOGDIR/kyc.log) ==="
( cd "$ROOT/kyc-service" && KYC_KAFKA_ENABLED=true KYC_INTERNAL_HMAC_SECRET=e2e-internal \
  KYC_VERIFIER_HMAC_SECRET=e2e-verifier KAFKA_BOOTSTRAP=localhost:9092 \
  mvn -q spring-boot:run -Dspring-boot.run.arguments=--logging.level.org.springframework.web=DEBUG ) > "$LOGDIR/kyc.log" 2>&1 &
PIDS+=($!)

echo "=== Bật wallet-service (log: $LOGDIR/wallet.log) ==="
( cd "$ROOT/wallet-service" && WALLET_KAFKA_ENABLED=true WALLET_KYC_HMAC_SECRET=e2e-internal \
  WALLET_INTERNAL_HMAC_SECRET=e2e-internal WALLET_KYC_BASE_URL=http://localhost:8082 \
  KAFKA_BOOTSTRAP=localhost:9092 WALLET_BANK_MOCK=true \
  WALLET_RECONCILE_ENABLED=false \
  mvn -q spring-boot:run -Dspring-boot.run.arguments=--logging.level.org.springframework.web=DEBUG ) > "$LOGDIR/wallet.log" 2>&1 &
PIDS+=($!)

echo "=== Bật api-gateway (log: $LOGDIR/gateway.log) ==="
( cd "$ROOT/api-gateway" && GATEWAY_HMAC_SECRET=e2e-internal \
  GATEWAY_JWT_PUBLIC_KEY="$(cat "$D/pub.b64")" \
  mvn -q spring-boot:run -Dspring-boot.run.arguments=--logging.level.org.springframework.web=DEBUG ) > "$LOGDIR/gateway.log" 2>&1 &
PIDS+=($!)

# 3) Chờ 3 cổng sẵn sàng (tối đa ~180s)
echo "=== Chờ 3 service mở cổng (8080/8081/8082) ==="
ready() { lsof -tiTCP:$1 -sTCP:LISTEN >/dev/null 2>&1; }
for i in $(seq 1 180); do
  if ready 8080 && ready 8081 && ready 8082; then echo "  cả 3 cổng đã mở (sau ${i}s)"; break; fi
  sleep 1
done
if ! (ready 8080 && ready 8081 && ready 8082); then
  echo "  ❌ stack không lên kịp — dump tail log:"; tail -20 "$LOGDIR"/*.log; exit 1
fi
# HTTP-ready: chờ tới khi mỗi service THỰC SỰ phục vụ (Spring context init xong, không chỉ mở cổng).
echo "=== Chờ HTTP-ready (context init xong) ==="
http_ready() { # http_ready <url> — bất kỳ HTTP status nào (kể cả 4xx) = context đã phục vụ
  local code; code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$1" 2>/dev/null)
  [ "$code" != "000" ]
}
for i in $(seq 1 60); do
  if http_ready "$KYC/kyc/cases/ping/status" && http_ready "$GW/api/wallets/0" && http_ready "http://localhost:8080/wallets/0"; then
    echo "  cả 3 service phục vụ HTTP (sau ${i}s)"; break; fi
  sleep 1
done
sleep 2

# 4) Kịch bản: submit -> approve -> create -> topup -> withdraw VỚI X-Trace-Id cố định.
echo "=== [1] Submit KYC (direct -> kyc) ==="
B='{"userId":"user-1","documentRefs":["id-front","selfie"]}'
TS=$(date +%s); SIG=$(sign e2e-internal api-gateway POST /kyc/submissions "$TS" "$B")
R=$(curl -s -w '\n%{http_code}' -X POST "$KYC/kyc/submissions" \
  -H "X-Service-Id: api-gateway" -H "X-Timestamp: $TS" -H "X-Signature: $SIG" \
  -H "Content-Type: application/json" --data-raw "$B")
check "submit" "$(echo "$R" | tail -1)" "201"
SUBID=$(echo "$R" | head -1 | sed -E 's/.*"submissionId":"([^"]+)".*/\1/')

echo "=== [2] Approve webhook (direct -> kyc) ==="
B="{\"submissionId\":\"$SUBID\",\"decision\":\"APPROVE\",\"decidedBy\":\"verifier-x\",\"reason\":\"ok\"}"
TS=$(date +%s); SIG=$(sign e2e-verifier verifier POST /kyc/webhooks/decision "$TS" "$B")
R=$(curl -s -w '\n%{http_code}' -X POST "$KYC/kyc/webhooks/decision" \
  -H "X-Timestamp: $TS" -H "X-Signature: $SIG" \
  -H "Content-Type: application/json" --data-raw "$B")
check "approve" "$(echo "$R" | tail -1)" "200"

echo "=== [3] Create wallet (qua gateway) ==="
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets" \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" --data-raw '{"ownerName":"Alice"}')
check "create wallet" "$(echo "$R" | tail -1)" "201"
WID=$(echo "$R" | head -1 | sed -E 's/.*"id":([0-9]+).*/\1/')

echo "=== [4] Topup 100 (qua gateway) ==="
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$WID/topup" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: t1" \
  -H "Content-Type: application/json" --data-raw '{"amount":100}')
check "topup" "$(echo "$R" | tail -1)" "200"

echo "=== [5] ⭐ Withdraw 30 VỚI X-Trace-Id: $TRACE (qua gateway) — chạm cả 3 service ==="
# withdraw -> gateway (filter+forward) -> wallet (filter + KYC-gate HTTP) -> kyc (filter).
RESP_HEADERS=$(curl -s -D - -o /dev/null -X POST "$GW/api/wallets/$WID/withdraw" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: w1" -H "X-Trace-Id: $TRACE" \
  -H "Content-Type: application/json" --data-raw '{"amount":30}')
# gateway echo lại traceId trên response header -> sợi chỉ quay về client.
echo "$RESP_HEADERS" | grep -qi "X-Trace-Id: $TRACE" \
  && { echo "  ✅ gateway echo X-Trace-Id trên response = $TRACE"; pass=$((pass+1)); } \
  || { echo "  ❌ response KHÔNG echo X-Trace-Id=$TRACE"; fail=$((fail+1)); }

echo "=== chờ 3s cho log flush (KYC-gate HTTP đã chạy đồng bộ trong withdraw) ==="
sleep 3

echo "=== [6] ⭐ Sợi chỉ: grep '$TRACE' trong log CẢ 3 service ==="
grep_trace "gateway" "$LOGDIR/gateway.log"
grep_trace "wallet"  "$LOGDIR/wallet.log"
grep_trace "kyc"     "$LOGDIR/kyc.log"

echo ""
echo "================= KẾT QUẢ trace-thread: $pass PASS / $fail FAIL ================="
[ "$fail" -eq 0 ]
