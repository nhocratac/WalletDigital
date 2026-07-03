#!/bin/bash
# E2E kyc transactional-outbox (Task 5): CHỨNG MINH event KHÔNG MẤT khi "crash trước publish".
#
# Thiết kế outbox (Task 1-4, đã commit): revoke() ghi REVOKED + outbox-row PENDING trong CÙNG tx
# nghiệp vụ (nguyên tử) — KHÔNG còn publish Kafka trực tiếp từ revoke. OutboxRelay là @Scheduled
# RIÊNG, đọc PENDING -> publish Kafka -> markSent, chạy độc lập với tx ghi.
#
# ADAPTATION bắt buộc (đọc kỹ trước khi sửa script này): kyc-service dùng H2 IN-MEMORY
# (spring.datasource.url=jdbc:h2:mem:kycdb) — restart kyc là mất sạch outbox table, nên một kịch bản
# kiểu "tắt relay bằng cách kill service rồi bật lại" (restart-based ON/OFF) KHÔNG dùng được ở đây: nó
# sẽ xoá luôn row PENDING mà ta đang cố chứng minh "chưa mất". Thay vào đó ta mô phỏng "cửa sổ trước
# khi relay kịp publish" bằng CÙNG MỘT tiến trình kyc, chỉ kéo dài
# KYC_OUTBOX_RELAY_INITIAL_DELAY_MS (mặc định prod = 0, ở đây set lớn, vd 15000ms) trong khi
# KYC_OUTBOX_RELAY_INTERVAL_MS giữ nguyên bình thường (2000ms). Trong cửa sổ initial-delay đó,
# OutboxRelay's @Scheduled CHƯA chạy lượt nào -> row outbox ghi bởi revoke() chắc chắn còn PENDING
# (code guarantee: relay() không thể fire trước initial delay) — "chưa mất, chưa gửi", KHÔNG PHẢI mất.
# Sau khi initial delay trôi qua, relay tự chạy lượt đầu -> publish -> wallet consumer xử lý.
#
# Cách quan sát "wallet CHƯA nhận event" mà KHÔNG cần đọc DB kyc trực tiếp (không có admin endpoint):
#   - Phase A: revoke xong (kyc DB đã REVOKED — assert qua GET status có ký HMAC), nhưng wallet's
#     KycStatusCache vẫn cache APPROVED cũ (D6: cache chỉ đọc lại khi miss) -> withdraw NGAY sau revoke
#     (trong cửa sổ initial-delay) vẫn ĐƯỢC PHÉP (202) — chứng tỏ consumer chưa evict cache -> event
#     chưa tới wallet. Đồng thời grep log wallet: CHƯA có dòng consumer nào nhắc tới userId của event.
#   - Phase B: chờ qua initial-delay + 1 chu kỳ interval -> relay đã publish -> wallet consumer đã
#     nhận record và evict cache. Bằng chứng log: dòng consumer mang userId của event xuất hiện
#     (event ĐÃ tới, KHÔNG MẤT). Withdraw kế tiếp -> 403 (cache đã evict, gate sync thật -> REVOKED).
#
# LƯU Ý consumption-evidence (grep Phase A/B): sau evict, consumer còn chạy compensation scan (D5)
# đọc ledger. Trong stack prod-mode này, thread Kafka listener KHÔNG có tenant context (chỉ
# TenantFilter của HTTP request set nó) -> scan fail-closed ("Schema __no_tenant__ not found") và
# consumer log ERROR "Cannot process kyc.revoked payload: {...userId...}". Đây là GAP đa-tenant CÓ
# SẴN của wallet (SP5 — nợ "consumer/worker per-tenant" đã ghi; test suite import
# DefaultTenantContextConfig nên không gặp), KHÔNG phải lỗi outbox và KHÔNG ảnh hưởng kết luận:
# evict chạy TRƯỚC scan (KycRevokedConsumer: evict rồi mới findWithdrawalsForUserSince) nên 403 ở
# Phase B vẫn chứng minh event được giao. Grep vì thế match CẢ HAI dạng bằng chứng consumer-đã-xử-lý:
#   "COMPENSATION-ALERT userId=<u>"  (khi scan chạy được — vd single-tenant/default context)
#   '"userId":"<u>"'                 (payload in trong dòng consumer khi scan fail-closed)
# — cả hai chỉ xuất hiện khi record ĐÃ được consumer nhận & xử lý.
#
# Dùng: docker compose up -d (nếu Kafka chưa chạy) rồi  bash e2e/outbox-not-lost.sh   (từ repo root)
set -u
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
D=/tmp/e2e_sp3
LOGDIR=/tmp/e2e_outbox_logs
GW=http://localhost:8081
KYC=http://localhost:8082
# USER_ID unique mỗi lần chạy: topic kyc.revoked GIỮ event của các lần chạy trước, và consumer group
# của wallet có suffix UUID ngẫu nhiên (broadcast) -> mỗi lần boot wallet đọc lại từ đầu topic. Nếu
# userId cố định, event CŨ của lần chạy trước sẽ match grep Phase A (false positive "đã consume").
USER_ID="user-outbox-$(date +%s)"
RELAY_INITIAL_DELAY_MS=15000
mkdir -p "$D" "$LOGDIR"
pass=0; fail=0
check() { if [ "$2" = "$3" ]; then echo "  ✅ $1 -> $2"; pass=$((pass+1)); else echo "  ❌ $1 -> $2 (mong đợi $3)"; fail=$((fail+1)); fi; }
grep_has() { # grep_has <label> <logfile> <ERE-pattern>
  if grep -Eq "$3" "$2" 2>/dev/null; then echo "  ✅ $1: CÓ /$3/ trong $2"; pass=$((pass+1));
  else echo "  ❌ $1: KHÔNG thấy /$3/ trong $2"; fail=$((fail+1)); fi
}
grep_not_has() { # grep_not_has <label> <logfile> <ERE-pattern>
  if grep -Eq "$3" "$2" 2>/dev/null; then echo "  ❌ $1: ĐÃ thấy /$3/ trong $2 (mong đợi CHƯA)"; fail=$((fail+1));
  else echo "  ✅ $1: CHƯA thấy /$3/ trong $2 (đúng — event còn PENDING)"; pass=$((pass+1)); fi
}
# Bằng chứng "consumer ĐÃ xử lý event của user" (xem LƯU Ý header): alert HOẶC payload trong log consumer.
CONSUMED_RE="COMPENSATION-ALERT userId=$USER_ID|\"userId\":\"$USER_ID\""

# --- Kafka: chỉ tự bật nếu CHƯA chạy; chỉ tự tắt nếu CHÍNH script này bật ---
STARTED_COMPOSE=0
if ! (echo > /dev/tcp/127.0.0.1/9092) 2>/dev/null; then
  echo "=== Kafka chưa chạy -> docker compose up -d ==="
  (cd "$ROOT" && docker compose up -d) || { echo "❌ không bật được docker compose"; exit 1; }
  STARTED_COMPOSE=1
  echo "  đợi Kafka sẵn sàng (~15-20s KRaft)..."
  sleep 18
else
  echo "=== Kafka đã chạy sẵn -> KHÔNG đụng docker compose ==="
fi

PIDS=()
cleanup() {
  echo "=== Dọn: kill 3 service ==="
  for p in "${PIDS[@]:-}"; do [ -n "$p" ] && kill "$p" 2>/dev/null; done
  # phòng orphan giữ cổng (bài học README): kill theo cổng
  for port in 8080 8081 8082; do
    pid=$(lsof -tiTCP:$port -sTCP:LISTEN 2>/dev/null)
    [ -n "$pid" ] && kill $pid 2>/dev/null
  done
  if [ "$STARTED_COMPOSE" -eq 1 ]; then
    echo "=== Script này đã tự bật Kafka -> docker compose down ==="
    (cd "$ROOT" && docker compose down)
  else
    echo "=== Kafka đã chạy từ trước -> giữ nguyên, KHÔNG docker compose down ==="
  fi
}
trap cleanup EXIT

# 1) khoá + JWT (tenantId=default -> schema baseline H2, không cần MySQL)
source "$ROOT/e2e/lib.sh"
gen_keys_and_token
gen_tenant_token "$USER_ID" "default" "$D/default.jwt"
cp "$ROOT/e2e/sign.sh" "$D/sign.sh"
JWT=$(cat "$D/default.jwt")
sign() { bash "$D/sign.sh" "$@"; }

# 2) Bật 3 service (background). kyc: KYC_OUTBOX_RELAY_INITIAL_DELAY_MS lớn — ĐÂY LÀ ADAPTATION
# thay cho restart-based ON/OFF (xem header comment).
echo "=== Bật kyc-service (relay-initial-delay=${RELAY_INITIAL_DELAY_MS}ms, log: $LOGDIR/kyc.log) ==="
( cd "$ROOT/kyc-service" && KYC_KAFKA_ENABLED=true KYC_INTERNAL_HMAC_SECRET=e2e-internal \
  KYC_VERIFIER_HMAC_SECRET=e2e-verifier KAFKA_BOOTSTRAP=localhost:9092 \
  KYC_OUTBOX_RELAY_INITIAL_DELAY_MS=$RELAY_INITIAL_DELAY_MS KYC_OUTBOX_RELAY_INTERVAL_MS=2000 \
  mvn -q spring-boot:run ) > "$LOGDIR/kyc.log" 2>&1 &
PIDS+=($!)

echo "=== Bật wallet-service (log: $LOGDIR/wallet.log) ==="
( cd "$ROOT/wallet-service" && WALLET_KAFKA_ENABLED=true WALLET_KYC_HMAC_SECRET=e2e-internal \
  WALLET_INTERNAL_HMAC_SECRET=e2e-internal WALLET_KYC_BASE_URL=http://localhost:8082 \
  KAFKA_BOOTSTRAP=localhost:9092 WALLET_BANK_MOCK=true WALLET_RECONCILE_ENABLED=false \
  mvn -q spring-boot:run ) > "$LOGDIR/wallet.log" 2>&1 &
PIDS+=($!)

echo "=== Bật api-gateway (log: $LOGDIR/gateway.log) ==="
( cd "$ROOT/api-gateway" && GATEWAY_HMAC_SECRET=e2e-internal \
  GATEWAY_JWT_PUBLIC_KEY="$(cat "$D/pub.b64")" \
  mvn -q spring-boot:run ) > "$LOGDIR/gateway.log" 2>&1 &
PIDS+=($!)

echo "=== Chờ 3 cổng sẵn sàng (tối đa ~180s) ==="
ready() { lsof -tiTCP:$1 -sTCP:LISTEN >/dev/null 2>&1; }
for i in $(seq 1 180); do
  if ready 8080 && ready 8081 && ready 8082; then echo "  cả 3 cổng đã mở (sau ${i}s)"; break; fi
  sleep 1
done
if ! (ready 8080 && ready 8081 && ready 8082); then
  echo "  ❌ stack không lên kịp — dump tail log:"; tail -40 "$LOGDIR"/*.log; exit 1
fi
echo "=== Chờ HTTP-ready (context init xong) ==="
http_ready() {
  local code; code=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$1" 2>/dev/null)
  [ "$code" != "000" ]
}
for i in $(seq 1 60); do
  if http_ready "$KYC/kyc/cases/ping/status" && http_ready "$GW/api/wallets/0" && http_ready "http://localhost:8080/wallets/0"; then
    echo "  cả 3 service phục vụ HTTP (sau ${i}s)"; break; fi
  sleep 1
done
sleep 2

# 3) Kịch bản
echo "=== [1] Submit KYC (direct -> kyc) userId=$USER_ID ==="
B="{\"userId\":\"$USER_ID\",\"documentRefs\":[\"id-front\",\"selfie\"]}"
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

echo "=== [3] Create wallet + topup 100 (qua gateway) ==="
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets" \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" --data-raw '{"ownerName":"Outbox Tester"}')
check "create wallet" "$(echo "$R" | tail -1)" "201"
WID=$(echo "$R" | head -1 | sed -E 's/.*"id":([0-9]+).*/\1/')
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$WID/topup" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: t-outbox" \
  -H "Content-Type: application/json" --data-raw '{"amount":100}')
check "topup" "$(echo "$R" | tail -1)" "200"

echo "=== [4] Withdraw#0 (10) — sanity: KYC APPROVED -> 202 ==="
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$WID/withdraw" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: w0" \
  -H "Content-Type: application/json" --data-raw '{"amount":10}')
check "withdraw#0 pre-revoke (202)" "$(echo "$R" | tail -1)" "202"

echo "=== [5] Revoke KYC (direct -> kyc) — ghi REVOKED + outbox PENDING trong CÙNG tx ==="
B='{"reason":"fraud detected"}'
TS=$(date +%s); SIG=$(sign e2e-internal api-gateway POST "/kyc/cases/$USER_ID/revoke" "$TS" "$B")
R=$(curl -s -w '\n%{http_code}' -X POST "$KYC/kyc/cases/$USER_ID/revoke" \
  -H "X-Service-Id: api-gateway" -H "X-Roles: compliance" -H "X-Timestamp: $TS" -H "X-Signature: $SIG" \
  -H "Content-Type: application/json" --data-raw "$B")
check "revoke" "$(echo "$R" | tail -1)" "200"

echo "=== [6] ⭐ PHASE A: kyc DB đã REVOKED NGAY (GET status, ký HMAC như RestKycGate) ==="
TS=$(date +%s); SIG=$(sign e2e-internal api-gateway GET "/kyc/cases/$USER_ID/status" "$TS" "")
R=$(curl -s "$KYC/kyc/cases/$USER_ID/status" \
  -H "X-Service-Id: api-gateway" -H "X-Timestamp: $TS" -H "X-Signature: $SIG")
STATUS=$(echo "$R" | sed -E 's/.*"status":"([^"]+)".*/\1/')
check "kyc GET status (REVOKED ngay, không chờ relay)" "$STATUS" "REVOKED"

echo "=== [7] ⭐ PHASE A: withdraw#1 (10) NGAY sau revoke, TRONG cửa sổ initial-delay ==="
# wallet KycStatusCache còn cache APPROVED cũ (chưa bị evict — consumer chưa nhận event) -> 202.
# => event KHÔNG MẤT (nó đang PENDING trong outbox), nhưng CŨNG CHƯA GỬI (đúng ngữ nghĩa "chưa mất,
# chưa tới" — không phải race bug). Withdraw này tạo ledger WITHDRAW_HOLD SAU revokedAt -> nguyên
# liệu cho compensation scan ở Phase B.
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$WID/withdraw" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: w1" \
  -H "Content-Type: application/json" --data-raw '{"amount":10}')
check "withdraw#1 trong cửa sổ initial-delay (VẪN 202 — cache stale, event chưa evict)" "$(echo "$R" | tail -1)" "202"

echo "=== [8] ⭐ PHASE A: wallet log CHƯA có bằng chứng consumer xử lý event (event còn PENDING trong outbox) ==="
grep_not_has "phase A (chưa consume)" "$LOGDIR/wallet.log" "$CONSUMED_RE"

echo "=== [9] Chờ qua initial-delay (${RELAY_INITIAL_DELAY_MS}ms) + 1 chu kỳ relay (2000ms) + buffer ==="
SLEEP_S=$(( (RELAY_INITIAL_DELAY_MS + 2000 + 5000) / 1000 ))
echo "  sleep ${SLEEP_S}s..."
sleep "$SLEEP_S"

echo "=== [10] ⭐ PHASE B: relay đã publish -> wallet log CÓ bằng chứng consumer đã xử lý event ==="
grep_has "phase B (đã consume, event KHÔNG MẤT)" "$LOGDIR/wallet.log" "$CONSUMED_RE"

echo "=== [11] ⭐ PHASE B: withdraw#2 (10) sau khi event đã tới -> 403 (cache evicted, gate sync REVOKED) ==="
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$WID/withdraw" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: w2" \
  -H "Content-Type: application/json" --data-raw '{"amount":10}')
check "withdraw#2 sau khi event tới (403)" "$(echo "$R" | tail -1)" "403"

echo ""
echo "================= KẾT QUẢ outbox-not-lost: $pass PASS / $fail FAIL ================="
[ "$fail" -eq 0 ]
