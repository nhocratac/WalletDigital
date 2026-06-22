# Thiết kế: Observability Nấc 1 — TraceId propagation + MDC

- **Ngày:** 2026-06-22
- **Phạm vi:** Sợi chỉ traceId xuyên suốt **3 service** (gateway/wallet/kyc) + qua **HTTP** lẫn **Kafka** lẫn **worker** — đưa traceId vào **MDC** để mọi dòng log tự mang. **Nấc 1** (correlation ID); span/OpenTelemetry (nấc 2) ngoài scope.
- **Tiền đề:** gateway đã có `TraceIdFilter` (sinh/forward `X-Trace-Id`) nhưng **chưa dùng MDC**; wallet/kyc **không** đọc traceId, **không ai** dùng MDC. (Lỗ hổng đã bắt cùng lúc với gap X-User-Id.)
- **Mục tiêu học:** correlation ID, MDC (ThreadLocal), continue-or-generate ở mọi entry, propagation qua HTTP header / Kafka header / worker, ThreadLocal cleanup, traceId opaque (không nhúng PII).

> **Nguồn gốc:** quyết định OB1–OB8 **do người học tự suy ra** trong phiên Socratic trước (filter→MDC→interceptor→Kafka header→worker; continue-or-generate mọi entry; format opaque). Doc này gấp lại để chốt + plan. Diagram ở `.html`.

---

## 1. Vấn đề

Một hành động user (`withdraw`) đi qua **3 service**, mỗi service log riêng. Khi cần debug, không có cách nối log của *cùng một request* xuyên service. Gateway sinh `X-Trace-Id` rồi **forward**, nhưng wallet/kyc **vứt** → sợi chỉ đứt ngay chặng đầu; và **không ai** nhét traceId vào log (chưa dùng MDC) → traceId chỉ "sống" trong gateway như request-attribute.

→ Cần: traceId **nối liền** qua HTTP + Kafka + worker, và **tự xuất hiện ở mọi dòng log** (qua MDC + log pattern).

---

## 2. Bảng quyết định (OB1–OB8 từ Socratic)

| # | Quyết định | Lý do (đã tự suy ra) |
|---|---|---|
| OB1 | **Mỗi service** có `TraceIdFilter`: đọc `X-Trace-Id`; **không có → SINH (UUID)** (continue-or-generate); đặt vào **MDC**. Chạy SỚM NHẤT (trước cả HmacVerifyFilter) để log 401/lỗi cũng có traceId. | Không phải mọi luồng qua gateway (webhook verifier→kyc, webhook bank→wallet, worker, Kafka) → sinh-nếu-thiếu phải là **quy tắc chung mọi entry**, không là đặc quyền gateway. Set sớm để mọi log (kể cả auth-reject) mang traceId. |
| OB2 | **Log pattern** kèm `[%X{traceId}]` ở cả 3 service → mọi dòng log tự mang traceId. | MDC + pattern = không phải truyền traceId tay vào từng câu log. |
| OB3 | **Clear MDC trong `finally`** (cùng filter set). | MDC là ThreadLocal; thread tái dùng → không clear → dòng log request sau mang traceId cũ (đúng bẫy T4 của TenantContext). |
| OB4 | **Outbound HTTP:** `ClientHttpRequestInterceptor` đăng ký trên `RestClient` → đọc MDC → đính `X-Trace-Id` vào MỌI request đi ra (wallet `RestKycGate`/`RestBankClient`). | Đối xứng inbound: một chỗ chèn cho mọi outbound call, khỏi rải rác từng adapter. (Cùng họ với ý gộp HMAC vào interceptor — nhưng shared-hmac tách riêng.) |
| OB5 | **Kafka:** producer (kyc) đặt traceId vào **message HEADER**; consumer (wallet) đọc header → MDC (sinh nếu thiếu), clear finally. | Truyền qua **header** Kafka, KHÔNG nhét vào payload (payload = dữ liệu domain; header = metadata xuyên suốt — như HTTP). Nối trace của cú revoke (đã có traceId) sang consumer. |
| OB6 | **Thread không-request** (reconciliation worker, idempotency purge): KHÔNG có upstream → **sinh traceId mới (root)** mỗi vòng → MDC, clear finally. | Worker chạy vì đồng hồ, không ai truyền traceId → tự sinh (như gateway sinh khi request chưa có). Đặt/clear trong vòng lặp per-tenant (như TenantContext). |
| OB7 | **Format = chuỗi random opaque (UUID)** — KHÔNG nhúng `tenantId`/`userId`/PII. | traceId đi khắp log + hệ tracing bên thứ ba → nhúng PII = rò; nhúng nghĩa = mục (trace đa-tenant ở worker). Context để lọc (tenantId/userId) là **MDC key RIÊNG**, chịu kỷ luật PII riêng. |
| OB8 | **Đây là Nấc 1 (correlation ID).** Span/duration/parentId (OTel — Nấc 2) **ngoài scope** → SP riêng. | Hand-roll span = tự chế lại OTel; nấc 1 rẻ + đặt nền propagation cho OTel sau. |

---

## 3. Vòng propagation hoàn chỉnh (ASCII)

```
HTTP request ─X-Trace-Id─► [TraceIdFilter] đọc/sinh → MDC ──► mọi log [%X{traceId}]
                                  │ finally: MDC.clear()
                                  ├─ outbound HTTP: [Interceptor] MDC → X-Trace-Id header ─► service kế
                                  └─ publish Kafka: producer → record header traceId ─► consumer đọc → MDC

Worker @Scheduled (không request): sinh traceId mới (root) → MDC → log → finally clear
```

Đối xứng: **mọi điểm DỮ LIỆU ĐI VÀO một thread** (HTTP request / Kafka message / tick đồng hồ) có một chỗ đặt MDC; **mọi điểm ĐI RA** (HTTP call / Kafka publish) có một chỗ đọc MDC.

---

## 4. Phạm vi từng service

| Service | Hiện có | Thêm |
|---|---|---|
| **gateway** | `TraceIdFilter` (sinh/forward, request-attribute) | đặt traceId vào **MDC** + log pattern (đang là attribute, chưa MDC) |
| **wallet** | không | `TraceIdFilter`→MDC (sớm nhất, trước HmacVerifyFilter) + log pattern + **interceptor outbound** (RestKycGate/RestBankClient) + **Kafka consumer** đọc header→MDC + **worker** sinh root |
| **kyc** | không | `TraceIdFilter`→MDC + log pattern + **Kafka producer** đặt header traceId |

> **Thứ tự filter wallet (sau Stage 4):** `TraceIdFilter` (sớm nhất, @Order thấp hơn 0) → `HmacVerifyFilter @Order(0)` → `TenantFilter @Order(1)`. → log của cú 401 (HMAC sai) vẫn có traceId.

---

## 5. Nợ kỹ thuật & YAGNI
- **OTel / span (Nấc 2)** — SP riêng; nền propagation header này tái dùng được (W3C `traceparent` thay `X-Trace-Id`).
- **Gộp interceptor traceId + HMAC** thành một (shared-hmac) — chờ tách shared-hmac module (parent POM); giờ interceptor traceId riêng.
- **MDC key phụ** (`tenantId`/`userId` để lọc log) — thêm nếu cần, kèm kỷ luật PII (mask/hash); ngoài scope nấc 1.
- Sampling (chỉ ghi % trace) — chưa cần ở quy mô học.

---

## 6. Chiến lược kiểm thử

```
Unit (mỗi service):
  · TraceIdFilter: có X-Trace-Id → MDC = đúng giá trị; không có → MDC có UUID mới
  · finally: sau request MDC trống (clear); request lỗi/exception vẫn clear
  · thread-reuse: request 1 (trace A) rồi request 2 (không header) → request 2 KHÔNG mang A
Outbound (wallet, MockWebServer):
  · RestKycGate/RestBankClient call → request đi ra CÓ header X-Trace-Id = traceId trong MDC
Kafka (EmbeddedKafka):
  · kyc publish kyc.revoked → record có header traceId; wallet consume → MDC = traceId đó
  · message không header traceId → consumer sinh mới (không lỗi)
Worker:
  · reconciliation/purge chạy → log có traceId (root mới mỗi vòng); clear sau mỗi tenant
Integration/e2e:
  · withdraw qua gateway → grep traceId trong log cả 3 service ra cùng một giá trị (sợi chỉ liền)
Regression: toàn bộ test SP1–Stage4 xanh.
```

---

## 7. Lộ trình triển khai (TDD)
1. **wallet `TraceIdFilter` + MDC + log pattern** (sớm nhất, continue-or-generate, clear finally). Test filter + thread-reuse.
2. **gateway: đưa traceId vào MDC + log pattern** (đã có filter, chỉ thêm MDC). **kyc: `TraceIdFilter` + MDC + log pattern.**
3. **Outbound interceptor** (wallet RestClient) → đính X-Trace-Id; test MockWebServer.
4. **Kafka:** producer (kyc) đặt header; consumer (wallet) đọc → MDC; test EmbeddedKafka.
5. **Worker** (reconciliation + purge): sinh root traceId per vòng; clear. Test log có traceId.
6. **e2e:** grep một traceId xuyên 3 service.
