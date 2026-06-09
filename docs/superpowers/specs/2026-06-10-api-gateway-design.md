# Thiết kế: API Gateway Service

- **Ngày:** 2026-06-10
- **Phạm vi:** MỘT microservice — `api-gateway` (chỉ KIỂM token + ĐỊNH TUYẾN; KHÔNG cấp token/login)
- **Mục tiêu:** Dự án học để rèn tư duy Software Architect (gateway pattern, JWT/RS256, service-to-service HMAC, xử lý lỗi downstream). Bổ trợ cho `wallet-service`.

> Tài liệu này là "hợp đồng thiết kế" trước khi code. Mọi quyết định đều kèm *lý do* và *đánh đổi*.

---

## 1. Bối cảnh & Vấn đề

`api-gateway` là **cửa ngõ** của hệ microservice: mọi request từ người dùng đều đi qua nó trước khi tới service nghiệp vụ (`wallet-service`...). Nó giải đúng phần "ngoài phạm vi" mà thiết kế wallet đã nhắc: xác thực *người dùng* và biến danh tính đó thành thông tin đáng tin cho các service phía sau.

Gateway này **không cấp token** (không có `/login`). Token coi như do một IdP ngoài cấp. Gateway chỉ: verify JWT → bóc tenant → ký HMAC → forward.

```
Người dùng --JWT(RS256)--> [api-gateway] --HMAC + X-Tenant-Id--> [wallet-service]
                            (TA XÂY)                              (đã có)
```

---

## 2. Các quyết định kiến trúc & lý do

| Hạng mục | Lựa chọn | Lý do / Đánh đổi |
|---|---|---|
| Cách xây | **Tự xây bằng Spring Boot MVC** (filter + RestClient) | Thấy rõ cơ chế gateway (verify→bóc→ký→forward); stack đồng bộ wallet, không phải học reactive. Đánh đổi: không tối ưu cho tải rất lớn như Spring Cloud Gateway. |
| Phạm vi | Chỉ KIỂM token + ĐỊNH TUYẾN (không login) | Single responsibility: cấp token là việc của IdP/auth-service riêng. |
| Thuật toán JWT | **RS256 (bất đối xứng)** | IdP giữ private key (ký); gateway chỉ có public key (verify) → gateway *không thể* giả token. Tách quyền "tạo" vs "kiểm". |
| Cấu trúc code | Clean Architecture **nhẹ tay** | Gateway gần như không có nghiệp vụ → domain mỏng, chỉ giữ port cho 2 mảnh dễ thay (TokenVerifier, DownstreamClient). Tránh over-engineering. |
| Ký gọi nội bộ | **HMAC** (hợp đồng chung với wallet) | Cùng `canonical` + tên header mà `wallet-service` verify. |
| Nguồn tenant | **Bóc từ claim JWT** (không từ client) | Người dùng không thể tự khai tenant; gateway đặt `X-Tenant-Id` dựa trên token đã ký. |
| Observability | TraceId truyền tiếp / sinh mới | Nối log xuyên suốt giữa gateway và downstream. |
| Resilience | Circuit Breaker **hoãn** (ghi note) | Gateway LÀ nơi có outbound call → là chỗ circuit breaker thuộc về. Thêm ở stage sau (Resilience4j) bọc lời gọi downstream. |

---

## 3. Luồng request (Data Flow)

```
  Authorization: Bearer <JWT>
  POST /api/wallets/1/topup
        │
        ▼
   [1] JwtAuthFilter — verify chữ ký JWT bằng PUBLIC key
        Sai / hết hạn / thiếu  → 401, CHẶN (không forward)
   [2] Bóc claims: userId (sub), tenantId
   [3] RouteTable: "/api/wallets/**" → http://localhost:8080 (wallet-service)
        Không khớp route → 404
   [4] HmacRequestSigner — dựng canonical + ký, GẮN header:
        X-Service-Id: api-gateway
        X-Timestamp:  <epoch>
        X-Signature:  HMAC-SHA256(sharedSecret, canonical)
        X-Tenant-Id:  <tenantId từ JWT>     (KHÔNG lấy từ client)
        X-Trace-Id:   <truyền tiếp / sinh mới>
   [5] RestClient forward → nhận response → trả về client
        Downstream 5xx → 502 · timeout/không kết nối → 504
```

`canonical = serviceId + "\n" + method + "\n" + path + "\n" + timestamp + "\n" + sha256(body)`

**Hai điểm cốt lõi:**
1. **Tenant lấy từ JWT, không từ client** — mảnh ghép còn thiếu của bảo mật: người dùng không thể giả tenant.
2. **`canonical` + tên header là HỢP ĐỒNG chung với wallet** — lệch một ký tự là wallet trả 401. Lý tưởng tách thư viện `shared-hmac` để hai bên dùng chung một hàm `buildCanonical()`.

---

## 4. Cấu trúc code (Clean Architecture — áp NHẸ)

> Gateway gần như không có nghiệp vụ → domain mỏng là ĐÚNG. Giá trị Clean Architecture tỉ lệ với độ phức tạp nghiệp vụ. Chỉ giữ *port* ở 2 chỗ dễ thay đổi.

```
com.vng.gateway
├── GatewayApplication.java
├── domain/                          ← MỎNG: value object + 2 port
│   ├── AuthenticatedCaller.java     · userId + tenantId (bóc từ JWT) — thuần Java
│   ├── TokenVerifier.java           · PORT: String token → AuthenticatedCaller (hoặc ném lỗi)
│   └── DownstreamClient.java        · PORT: forward request đã ký → trả response
├── application/
│   └── GatewayService.java          · điều phối: verify → chọn route → ký → forward
└── infrastructure/
    ├── security/
    │   ├── JwtAuthFilter.java        · filter CHẶN: gọi TokenVerifier, 401 nếu sai (chạy đầu)
    │   ├── JwtTokenVerifier.java     · ADAPTER cài TokenVerifier (RS256, public key)
    │   └── HmacRequestSigner.java    · dựng canonical + ký — HỢP ĐỒNG chung với wallet
    ├── routing/
    │   ├── RouteTable.java           · map "/api/wallets/**" → http://localhost:8080
    │   ├── RestClientDownstream.java · ADAPTER cài DownstreamClient (Spring RestClient)
    │   └── ForwardingController.java · controller "bắt tất cả" → gọi GatewayService
    ├── observability/
    │   └── TraceIdFilter.java        · đảm bảo X-Trace-Id
    └── config/
        └── GatewayProperties.java    · nạp routes + hmac secret + jwt public key từ application.yml
```

**Hai port = hai chỗ dễ thay đổi:** `TokenVerifier` (đổi IdP/cách verify) và `DownstreamClient` (đổi RestClient→WebClient, hay chuyển sang Spring Cloud Gateway). Đặt port đúng *chỗ sẽ thay đổi* là nghệ thuật của thiết kế.

---

## 5. JWT/RS256 & HMAC (chi tiết)

### Verify JWT (RS256)
`JwtTokenVerifier` (dùng `jjwt` hoặc Nimbus):
1. Tách token từ `Authorization: Bearer <token>`.
2. Verify CHỮ KÝ bằng public key → sai → `InvalidTokenException`.
3. Kiểm `exp` còn hạn → hết → `ExpiredTokenException`.
4. (tùy chọn) kiểm `issuer`/`audience`.
5. Bóc claims → `AuthenticatedCaller(userId, tenantId)`.

> **Test khi chưa có IdP thật:** sinh sẵn cặp khoá RSA; test helper ký token giả lập bằng PRIVATE key, gateway verify bằng PUBLIC key.

### Ký HMAC (hợp đồng với wallet)
Gateway gắn: `X-Service-Id`, `X-Timestamp`, `X-Signature`, `X-Tenant-Id`, `X-Trace-Id`. `canonical` y hệt mục 3.

> **Tại sao RS256 cho JWT nhưng HMAC cho gọi nội bộ?** JWT cần "nhiều bên verify, một bên ký" → bất đối xứng. Gọi nội bộ giữa 2 service tin nhau → đối xứng (HMAC) đơn giản và đủ.

---

## 6. Xử lý lỗi

| Tình huống | HTTP trả client | Ghi chú |
|---|---|---|
| Thiếu/sai/hết hạn JWT | `401 Unauthorized` | Chặn ngay, không forward |
| Không khớp route nào | `404 Not Found` | Path không thuộc service nào |
| Downstream trả 5xx | `502 Bad Gateway` | Lỗi từ service phía sau |
| Downstream timeout / không kết nối | `504 Gateway Timeout` | wallet chết/chậm |
| Lỗi nội bộ gateway | `500` | Bug của chính gateway |

> **502/504 chính là chỗ Circuit Breaker thuộc về.** Khi downstream liên tục timeout, breaker "mở" để gateway ngừng gọi và trả nhanh `503`, tránh dồn request làm sập thêm. (Stage sau, dùng Resilience4j.)

---

## 7. Chiến lược kiểm thử

```
Unit test (nhanh, không mạng):
  · JwtTokenVerifier: token hợp lệ → AuthenticatedCaller đúng; sai chữ ký → lỗi; hết hạn → lỗi.
      (Ký token test bằng PRIVATE key trong helper, verify bằng PUBLIC key.)
  · HmacRequestSigner: input cố định → đúng canonical + chữ ký kỳ vọng.
      ⭐ KHOÁ hợp đồng với wallet — ai đổi canonical, test đỏ.

Integration test (gateway chạy, downstream GIẢ LẬP bằng MockWebServer/WireMock):
  · ⭐ Đầu-cuối: request KÈM JWT hợp lệ → gateway verify, ký, forward
       → MockWebServer nhận request có đúng X-Tenant-Id (khớp JWT), X-Signature hợp lệ, X-Service-Id.
  · Không có JWT → 401, MockWebServer KHÔNG nhận gì.
  · Downstream trả 500 → gateway trả 502.   ·   Downstream timeout → 504.
```

> **Giả lập "hàng xóm" (MockWebServer/WireMock)** là kỹ năng test sống còn trong microservice: kiểm soát được downstream trả gì để test mọi nhánh lỗi, không cần dựng cả hệ thống thật.

---

## 8. Nợ kỹ thuật & YAGNI

**Nợ kỹ thuật / để stage sau:**
- **Circuit Breaker** (Resilience4j) bọc lời gọi downstream — gateway là nơi nó thuộc về.
- Tách **thư viện `shared-hmac`** để gateway và wallet dùng chung `buildCanonical()` (chống lệch hợp đồng).
- Lấy public key động từ JWKS endpoint của IdP (giờ: nạp tĩnh từ config).

**Cố tình chưa làm:**
- Cấp token / login (thuộc IdP/auth-service riêng).
- Rate limiting, request/response logging nâng cao, nhiều downstream route (giờ chỉ wallet).
- Chuyển sang Spring Cloud Gateway (sau khi đã hiểu cơ chế).

---

## 9. Lộ trình triển khai đề xuất

1. **Khởi tạo project + AuthenticatedCaller + 2 port** (domain mỏng).
2. **JwtTokenVerifier (RS256)** + unit test (ký bằng private key test).
3. **HmacRequestSigner** + unit test khoá hợp đồng canonical.
4. **JwtAuthFilter** (401 khi token sai) + RouteTable + GatewayProperties.
5. **GatewayService + ForwardingController + RestClientDownstream** (forward thật).
6. **Integration test** với MockWebServer: đầu-cuối, 401, 502, 504.
7. (Stage sau) Circuit Breaker + shared-hmac.
