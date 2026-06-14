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

# poll_state <walletId> <orderId> <expectedState> -> in PASS/FAIL khi đạt (hoặc timeout 20s)
poll_state() {
  local wid="$1" oid="$2" want="$3" i st
  for i in $(seq 1 40); do
    st=$(curl -s "$GW/api/wallets/$wid/withdrawals/$oid" -H "Authorization: Bearer $JWT" \
      | sed -E 's/.*"state":"([^"]+)".*/\1/')
    [ "$st" = "$want" ] && break
    sleep 0.5
  done
  check "poll order#$oid -> $want" "$st" "$want"
}
# balance_of <walletId> -> in ra balance (total)
balance_of() {
  curl -s "$GW/api/wallets/$1" -H "Authorization: Bearer $JWT" \
    | sed -E 's/.*"balance":([0-9.]+).*/\1/'
}

echo "=== [5] Withdraw 30 — KYC APPROVED -> 202 Accepted + order PENDING (E1) ==="
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$WID/withdraw" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: w1" \
  -H "Content-Type: application/json" --data-raw '{"amount":30}')
CODE=$(echo "$R" | tail -1); BODY=$(echo "$R" | head -1)
check "withdraw#1 (202 accepted)" "$CODE" "202"
OID1=$(echo "$BODY" | sed -E 's/.*"orderId":([0-9]+).*/\1/')
echo "  orderId=$OID1  body=$BODY"

echo "=== [5a] MockBank SETTLED -> worker đối soát lái order tới SETTLED ==="
poll_state "$WID" "$OID1" "SETTLED"
# settle: total 100 - 30 = 70 (tiền thật rời hệ).
check "balance after settle" "$(balance_of "$WID")" "70.0"

echo "=== [5b] MockBank REJECTED -> rút 20 -> worker refund -> FAILED, available phục hồi ==="
# Đặt kết quả bank mặc định = REJECTED (vòi điều khiển mock, chỉ bật khi wallet.bank.mock=true).
curl -s -o /dev/null -X POST "http://localhost:8080/mock-bank/default?result=REJECTED"
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$WID/withdraw" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: w-rej" \
  -H "Content-Type: application/json" --data-raw '{"amount":20}')
CODE=$(echo "$R" | tail -1); BODY=$(echo "$R" | head -1)
check "withdraw#reject (202 accepted)" "$CODE" "202"
OID2=$(echo "$BODY" | sed -E 's/.*"orderId":([0-9]+).*/\1/')
poll_state "$WID" "$OID2" "FAILED"
# refund: total không đổi (vẫn 70) — tiền chưa rời hệ, escrow trả về available.
check "balance after refund" "$(balance_of "$WID")" "70.0"
# Khôi phục SETTLED cho mọi withdraw sau (không ảnh hưởng các bước KYC kế).
curl -s -o /dev/null -X POST "http://localhost:8080/mock-bank/default?result=SETTLED"

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
