#!/bin/bash
# E2E SP3: submit -> approve -> create wallet -> topup -> withdraw OK -> revoke -> withdraw 403
D=/tmp/e2e_sp3
GW=http://localhost:8081
KYC=http://localhost:8082
JWT=$(cat "$D/user.jwt")
sign() { bash "$D/sign.sh" "$@"; }
pass=0; fail=0
check() { # check <label> <actual> <expected> — so khớp chuỗi
  if [ "$2" = "$3" ]; then echo "  ✅ $1 -> $2"; pass=$((pass+1));
  else echo "  ❌ $1 -> $2 (mong đợi $3)"; fail=$((fail+1)); fi
}
check_num() { # check_num <label> <actual> <expected> — so khớp SỐ (70.00 == 70.0)
  if awk -v a="$2" -v b="$3" 'BEGIN{exit !(a+0==b+0)}'; then echo "  ✅ $1 -> $2"; pass=$((pass+1));
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

echo "=== [4b] SP6 Transfer 30 (THROUGH gateway) — A->B tức thời, double-entry, tổng bảo toàn ==="
# Tạo ví NHẬN thứ hai (cùng user qua gateway — receiver không cần KYC; sender đã APPROVED ở [2]).
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets" \
  -H "Authorization: Bearer $JWT" -H "Content-Type: application/json" --data-raw '{"ownerName":"Bob"}')
check "create receiver wallet" "$(echo "$R" | tail -1)" "201"
WID2=$(echo "$R" | head -1 | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "  receiverWalletId=$WID2"
# Topup thêm 30 vào A để sau khi transfer 30 ra, A trở về 100 — giữ nguyên các assertion withdraw [5] phía sau.
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$WID/topup" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: t-xfer-seed" \
  -H "Content-Type: application/json" --data-raw '{"amount":30}')
check "topup A +30 (seed for transfer)" "$(echo "$R" | tail -1)" "200"
# balance trước transfer
A_BEFORE=$(curl -s "$GW/api/wallets/$WID" -H "Authorization: Bearer $JWT" | sed -E 's/.*"balance":([0-9.]+).*/\1/')
B_BEFORE=$(curl -s "$GW/api/wallets/$WID2" -H "Authorization: Bearer $JWT" | sed -E 's/.*"balance":([0-9.]+).*/\1/')
# transfer 30 A->B
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$WID/transfer" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: xfer-1" \
  -H "Content-Type: application/json" --data-raw "{\"toWalletId\":$WID2,\"amount\":30}")
CODE=$(echo "$R" | tail -1); BODY=$(echo "$R" | head -1)
check "transfer A->B (200)" "$CODE" "200"
echo "  body: $BODY"
A_AFTER=$(curl -s "$GW/api/wallets/$WID" -H "Authorization: Bearer $JWT" | sed -E 's/.*"balance":([0-9.]+).*/\1/')
B_AFTER=$(curl -s "$GW/api/wallets/$WID2" -H "Authorization: Bearer $JWT" | sed -E 's/.*"balance":([0-9.]+).*/\1/')
# A trừ 30, B cộng 30 — tổng (A+B) bảo toàn (tiền chỉ đổi chủ).
check_num "A balance after transfer (100-30)" "$A_AFTER" "$(awk -v a="$A_BEFORE" 'BEGIN{print a-30}')"
check_num "B balance after transfer (+30)" "$B_AFTER" "$(awk -v b="$B_BEFORE" 'BEGIN{print b+30}')"
check_num "tong A+B bao toan" "$(awk -v a="$A_AFTER" -v b="$B_AFTER" 'BEGIN{print a+b}')" \
  "$(awk -v a="$A_BEFORE" -v b="$B_BEFORE" 'BEGIN{print a+b}')"
# replay cùng key -> KHÔNG chuyển lần hai (idempotent), A giữ nguyên sau replay.
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$WID/transfer" \
  -H "Authorization: Bearer $JWT" -H "Idempotency-Key: xfer-1" \
  -H "Content-Type: application/json" --data-raw "{\"toWalletId\":$WID2,\"amount\":30}")
check "transfer replay same key (200)" "$(echo "$R" | tail -1)" "200"
A_REPLAY=$(curl -s "$GW/api/wallets/$WID" -H "Authorization: Bearer $JWT" | sed -E 's/.*"balance":([0-9.]+).*/\1/')
check_num "A balance unchanged after replay (idempotent)" "$A_REPLAY" "$A_AFTER"

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
check_num "balance after settle" "$(balance_of "$WID")" "70.0"

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
check_num "balance after refund" "$(balance_of "$WID")" "70.0"
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
