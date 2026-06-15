#!/bin/bash
# E2E SP5 (Task 8): HAI tenant cách ly hoàn toàn qua gateway.
#
# Onboard acme + globex (POST /admin/tenants thẳng vào wallet, kênh admin role=ops) ->
# tạo ví + topup ở acme bằng JWT acme (qua gateway) -> dùng JWT globex truy cập ví của acme -> 404
# (schema-per-tenant: globex KHÔNG thấy schema acme) -> tạo ví globex độc lập, balance riêng.
#
# Chứng minh cô lập-bằng-CẤU-TRÚC: cùng walletId tồn tại ở hai schema khác nhau; JWT tenant chỉ
# nhìn thấy schema của tenant mình (TenantFilter đọc X-Tenant-Id gateway gắn -> routing datasource).
#
# Tiền đề: wallet chạy với MySQL (schema-per-tenant) + master registry. Onboarding tạo
# CREATE SCHEMA tenant_acme/tenant_globex + Flyway migrate (xem README mục SP5).
D=/tmp/e2e_sp3
GW=http://localhost:8081
WALLET=http://localhost:8080
ACME_JWT=$(cat "$D/acme.jwt")
GLOBEX_JWT=$(cat "$D/globex.jwt")
pass=0; fail=0
check() { # check <label> <actual> <expected>
  if [ "$2" = "$3" ]; then echo "  ✅ $1 -> $2"; pass=$((pass+1));
  else echo "  ❌ $1 -> $2 (mong đợi $3)"; fail=$((fail+1)); fi
}
check_num() { # check_num <label> <actual> <expected> — so khớp SỐ (50.00 == 50.0)
  if awk -v a="$2" -v b="$3" 'BEGIN{exit !(a+0==b+0)}'; then echo "  ✅ $1 -> $2"; pass=$((pass+1));
  else echo "  ❌ $1 -> $2 (mong đợi $3)"; fail=$((fail+1)); fi
}

echo "=== [1] Onboard 2 tenant (POST /admin/tenants -> wallet, X-Roles: ops) ==="
for T in acme globex; do
  R=$(curl -s -w '\n%{http_code}' -X POST "$WALLET/admin/tenants" \
    -H "X-Roles: ops" -H "X-Tenant-Id: $T" \
    -H "Content-Type: application/json" --data-raw "{\"tenantId\":\"$T\"}")
  CODE=$(echo "$R" | tail -1)
  # 201 lần đầu; 409 nếu đã onboard (re-run) — cả hai coi như "đã có schema".
  if [ "$CODE" = "201" ] || [ "$CODE" = "409" ]; then
    echo "  ✅ onboard $T -> $CODE"; pass=$((pass+1));
  else echo "  ❌ onboard $T -> $CODE (mong đợi 201/409)"; fail=$((fail+1)); fi
done

echo "=== [2] acme: tạo ví + topup 100 (qua gateway, JWT acme -> X-Tenant-Id: acme) ==="
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets" \
  -H "Authorization: Bearer $ACME_JWT" -H "Content-Type: application/json" --data-raw '{"ownerName":"Alice"}')
CODE=$(echo "$R" | tail -1); BODY=$(echo "$R" | head -1)
check "acme create wallet" "$CODE" "201"
ACME_WID=$(echo "$BODY" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "  acme walletId=$ACME_WID"
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$ACME_WID/topup" \
  -H "Authorization: Bearer $ACME_JWT" -H "Idempotency-Key: ta1" \
  -H "Content-Type: application/json" --data-raw '{"amount":100}')
check "acme topup 100" "$(echo "$R" | tail -1)" "200"

echo "=== [3] globex DÙNG JWT globex truy cập ví CỦA acme -> 404 (cô lập schema) ==="
# Cùng walletId, nhưng JWT globex -> X-Tenant-Id: globex -> routing trỏ schema tenant_globex,
# nơi KHÔNG có ví này -> 404. Đây là điểm cốt lõi của SP5: lộ-chéo tenant là bất khả thi.
R=$(curl -s -w '\n%{http_code}' "$GW/api/wallets/$ACME_WID" \
  -H "Authorization: Bearer $GLOBEX_JWT")
check "globex thấy ví acme" "$(echo "$R" | tail -1)" "404"

echo "=== [4] globex tạo ví ĐỘC LẬP + topup 50 ==="
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets" \
  -H "Authorization: Bearer $GLOBEX_JWT" -H "Content-Type: application/json" --data-raw '{"ownerName":"Bob"}')
CODE=$(echo "$R" | tail -1); BODY=$(echo "$R" | head -1)
check "globex create wallet" "$CODE" "201"
GLOBEX_WID=$(echo "$BODY" | sed -E 's/.*"id":([0-9]+).*/\1/')
echo "  globex walletId=$GLOBEX_WID"
R=$(curl -s -w '\n%{http_code}' -X POST "$GW/api/wallets/$GLOBEX_WID/topup" \
  -H "Authorization: Bearer $GLOBEX_JWT" -H "Idempotency-Key: tg1" \
  -H "Content-Type: application/json" --data-raw '{"amount":50}')
check "globex topup 50" "$(echo "$R" | tail -1)" "200"

echo "=== [5] Balance độc lập: acme=100, globex=50 (topup globex KHÔNG đụng acme) ==="
balance_of() { curl -s "$GW/api/wallets/$1" -H "Authorization: Bearer $2" \
  | sed -E 's/.*"balance":([0-9.]+).*/\1/'; }
check_num "acme balance"   "$(balance_of "$ACME_WID" "$ACME_JWT")"   "100.0"
check_num "globex balance" "$(balance_of "$GLOBEX_WID" "$GLOBEX_JWT")" "50.0"

echo ""
echo "================= KẾT QUẢ SP5 đa-tenant: $pass PASS / $fail FAIL ================="
