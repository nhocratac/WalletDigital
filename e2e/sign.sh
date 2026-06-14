#!/bin/bash
# Ký HMAC theo canonical chung: serviceId\nmethod\npath\ntimestamp\nsha256hex(body)
# Dùng: sign.sh <secret> <serviceId> <method> <path> <timestamp> <body>
set -e
secret="$1"; sid="$2"; method="$3"; path="$4"; ts="$5"; body="$6"
bodyhash=$(printf '%s' "$body" | openssl dgst -sha256 | awk '{print $NF}')
canonical=$(printf '%s\n%s\n%s\n%s\n%s' "$sid" "$method" "$path" "$ts" "$bodyhash")
printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$secret" | awk '{print $NF}'
