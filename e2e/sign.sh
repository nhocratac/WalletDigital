#!/bin/bash
# Ký HMAC theo canonical chung "identity-if-present" (Stage4 S2):
#   serviceId\nmethod\npath\ntimestamp\nsha256hex(body)[\nuserId\ntenantId]
# Dùng: sign.sh <secret> <serviceId> <method> <path> <timestamp> <body> [<userId> <tenantId>]
# userId/tenantId chỉ được APPEND khi CẢ HAI có (direct->kyc/webhook bank không truyền -> canonical cũ).
set -e
secret="$1"; sid="$2"; method="$3"; path="$4"; ts="$5"; body="$6"; userId="$7"; tenantId="$8"
bodyhash=$(printf '%s' "$body" | openssl dgst -sha256 | awk '{print $NF}')
canonical=$(printf '%s\n%s\n%s\n%s\n%s' "$sid" "$method" "$path" "$ts" "$bodyhash")
if [ -n "$userId" ] && [ -n "$tenantId" ]; then
  canonical=$(printf '%s\n%s\n%s' "$canonical" "$userId" "$tenantId")
fi
printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$secret" | awk '{print $NF}'
