#!/bin/bash
# E2E SP3: submit -> approve -> create wallet -> topup -> withdraw OK -> revoke -> withdraw 403
D=/tmp/e2e_sp3
GW=http://localhost:8081
KYC=http://localhost:8082
JWT=$(cat "$D/user.jwt")
sign() { bash "$D/sign.sh" "$@"; }
pass=0; fail=0
check() { # check <label> <actual> <expected>
  if [ "$2" = "$3" ]; then echo "  ✅ $1 -> $2"; pass=$((pass+1));
  else echo "  ❌ $1 -> $2 (mong đợi $3)"; fail=$((fail+1)); fi
}

echo "=== [1] Submit KYC (direct -> kyc, HMAC api-gateway) ==="
B='{"userId":"user-1","documentRefs":["id-front","selfie"]}'
TS=$(date +%s); SIG=$(sign e2e-internal api-gateway POST /kyc/submissions "$TS" "$B")
R=$(curl -s -w '\n%{http_code}' -X POST "$KYC/kyc/submissions" \
  -H "X-Service-Id: api-gateway" -H "X-Timestamp: $TS" -H "X-Signature: $SIG" \
  -H "Content-Type: application/json" --data-raw "$B")
CODE=$(echo "$R" | tail -1); BODY=$(echo "$R" | head -1)
check "submit status" "$CODE" "201"
SUBID=$(echo "$BODY" | sed -E 's/.*"submissionId":"([^"]+)".*/\1/')
echo "  submissionId=$SUBID"

echo "=== [2] Approve via webhook (direct -> kyc, HMAC verifier) ==="
B="{\"submissionId\":\"$SUBID\",\"decision\":\"APPROVE\",\"decidedBy\":\"verifier-x\",\"reason\":\"ok\"}"
TS=$(date +%s); SIG=$(sign e2e-verifier verifier POST /kyc/webhooks/decision "$TS" "$B")
R=$(curl -s -w '\n%{http_code}' -X POST "$KYC/kyc/webhooks/decision" \
  -H "X-Timestamp: $TS" -H "X-Signature: $SIG" \
  -H "Content-Type: application/json" --data-raw "$B")
check "webhook status" "$(echo "$R" | tail -1)" "200"
echo "  body: $(echo "$R" | head -1)"

echo "=== [3] Create wallet (THROUGH gateway, JWT -> X-User-Id) ==="
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets" \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" --data-raw '{"ownerName":"Alice"}')
CODE=$(echo "$R" | tail -1); BODY=$(echo "$R" | head -1)
check "create wallet" "$CODE" "201"
WID=$(echo "$BODY" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "  walletId=$WID  body=$BODY"

echo "=== [4] Topup 100 (THROUGH gateway) ==="
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$WID/topup" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: t1" \
  -H "Content-Type: application/json" --data-raw '{"amount":100}')
check "topup" "$(echo "$R" | tail -1)" "200"
echo "  body: $(echo "$R" | head -1)"

echo "=== [5] Withdraw 30 — KYC APPROVED -> phải 200 (gateway->wallet->kyc sync) ==="
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$WID/withdraw" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: w1" \
  -H "Content-Type: application/json" --data-raw '{"amount":30}')
check "withdraw#1 (approved)" "$(echo "$R" | tail -1)" "200"
echo "  body: $(echo "$R" | head -1)"

echo "=== [6] Revoke KYC (direct -> kyc, HMAC + X-Roles compliance) -> publish kyc.revoked ==="
B='{"reason":"fraud detected"}'
TS=$(date +%s); SIG=$(sign e2e-internal api-gateway POST /kyc/cases/user-1/revoke "$TS" "$B")
R=$(curl -s -w '\n%{http_code}' -X POST "$KYC/kyc/cases/user-1/revoke" \
  -H "X-Service-Id: api-gateway" -H "X-Roles: compliance" -H "X-Timestamp: $TS" -H "X-Signature: $SIG" \
  -H "Content-Type: application/json" --data-raw "$B")
check "revoke" "$(echo "$R" | tail -1)" "200"

echo "=== chờ 5s cho event kyc.revoked -> wallet consumer evict cache ==="
sleep 5

echo "=== [7] Withdraw 10 — sau revoke -> phải 403 (cache evicted, kyc sync REVOKED) ==="
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$WID/withdraw" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: w2" \
  -H "Content-Type: application/json" --data-raw '{"amount":10}')
CODE=$(echo "$R" | tail -1); BODY=$(echo "$R" | head -1)
check "withdraw#2 (revoked)" "$CODE" "403"
echo "  body: $BODY"

echo ""
echo "================= KẾT QUẢ: $pass PASS / $fail FAIL ================="
