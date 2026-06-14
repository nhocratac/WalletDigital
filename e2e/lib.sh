#!/bin/bash
# Thư viện dùng chung cho e2e SP3.
D=/tmp/e2e_sp3
INTERNAL_SECRET="e2e-internal"
VERIFIER_SECRET="e2e-verifier"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

sha256hex() { printf '%s' "$1" | openssl dgst -sha256 | awk '{print $NF}'; }

# hmac_sign <secret> <serviceId> <method> <path> <timestamp> <body>
# canonical = serviceId\nmethod\npath\ntimestamp\nsha256hex(body)
hmac_sign() {
  local secret="$1" sid="$2" method="$3" path="$4" ts="$5" body="$6"
  local bodyhash canonical
  bodyhash=$(sha256hex "$body")
  canonical=$(printf '%s\n%s\n%s\n%s\n%s' "$sid" "$method" "$path" "$ts" "$bodyhash")
  printf '%s' "$canonical" | openssl dgst -sha256 -hmac "$secret" | awk '{print $NF}'
}

gen_keys_and_token() {
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$D/priv.pem" 2>/dev/null
  # public key STANDARD base64 X.509 DER -> gateway.jwt-public-key
  openssl rsa -in "$D/priv.pem" -pubout -outform DER 2>/dev/null | openssl base64 -A > "$D/pub.b64"
  # JWT user (sub=user-1, tenantId=acme)
  local header payload now exp h p sig
  header='{"alg":"RS256","typ":"JWT"}'
  now=$(date +%s); exp=$((now + 3600))
  payload="{\"sub\":\"user-1\",\"tenantId\":\"acme\",\"iat\":$now,\"exp\":$exp}"
  h=$(printf '%s' "$header" | b64url)
  p=$(printf '%s' "$payload" | b64url)
  sig=$(printf '%s' "$h.$p" | openssl dgst -sha256 -sign "$D/priv.pem" -binary | b64url)
  printf '%s.%s.%s' "$h" "$p" "$sig" > "$D/user.jwt"
}
