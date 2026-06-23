# Thiết kế: Transactional Outbox — không mất event giữa commit ↔ publish

- **Ngày:** 2026-06-23
- **Phạm vi:** Trả nợ SP3/SP4: event Kafka **mất** nếu service crash giữa "commit DB" và "publish Kafka". Áp dụng đầu tiên cho **kyc `revoke` → `kyc.revoked`** (event sống còn: wallet xoá cache); pattern tổng quát cho mọi service phát event sau thay đổi DB.
- **Tiền đề:** SP3 (Kafka `kyc.revoked`, key=userId; wallet consumer idempotent D9) · SP7 (purge bảng phình) · không có outbox.
- **Mục tiêu học:** dual-write problem, transactional outbox, at-least-once + idempotent consumer, polling-publisher vs CDC, ordering, purge.

> **Nguồn gốc:** quyết định O1–O8 do người học suy ra (Socratic). Diagram ở `.html`.

---

## 1. Vấn đề: dual-write

kyc `revoke()` (`@Transactional`) ghi `REVOKED` vào DB **rồi** `KafkaKycEventPublisher.send(kyc.revoked)` — **hai hệ thống (DB + Kafka), không transaction chung**. Hai thứ tự, cùng hỏng:

```
A) commit DB TRƯỚC, publish SAU:  REVOKED ✓ ──💥── publish chưa kịp
   → DB=REVOKED nhưng event MẤT → wallet cache vẫn APPROVED → user bị revoke VẪN RÚT ĐƯỢC (mất event)
B) publish TRƯỚC, commit SAU:     publish ✓ ──💥── commit rollback
   → event đã gửi (wallet xoá cache) nhưng DB=APPROVED → event PHANTOM (nói chuyện chưa xảy ra)
```

**Giả định chung** (như SP6): DB và Kafka là **hai hệ thống tách rời, không có transaction chung** → không thể ghi nguyên tử vào cả hai. Phá giả định: đưa "event" vào **chính DB** để nguyên tử với nghiệp vụ.

---

## 2. Bảng quyết định (O1–O8 từ Socratic)

| # | Quyết định | Lý do (đã tự suy ra) |
|---|---|---|
| O1 | **Ghi event vào bảng `outbox` trong CÙNG transaction** với thay đổi nghiệp vụ (REVOKED + INSERT outbox = một tx). | DB+Kafka không nguyên tử được; nhưng REVOKED + outbox-row là **một DB, một tx** → nguyên tử (cùng commit hoặc cùng rollback). Bịt khe dual-write. |
| O2 | **Relay** riêng đọc `outbox WHERE status=PENDING` → publish Kafka → mark `SENT`. | Kafka ngoài DB → cần tiến trình đẩy từ bảng ra; tách khỏi luồng nghiệp vụ. |
| O3 | **At-least-once:** relay crash giữa send và mark-SENT → row vẫn PENDING → relay gửi LẠI → **trùng** event (không mất). | Bước relay vẫn là dual-write; chọn "thà trùng còn hơn mất". Mất-event = nguy hiểm; trùng = vô hại NẾU consumer idempotent. |
| O4 | **Consumer BẮT BUỘC idempotent** (điều kiện đứng của outbox). wallet `KycRevokedConsumer` đã idempotent tự nhiên (D9 — xoá cache 2 lần vô hại) → không phải đổi; **mọi consumer tương lai phải idempotent**. | Outbox cho at-least-once → trùng chỉ vô hại khi consumer idempotent. D9 đã đặt nền. |
| O5 | **Purge bảng outbox:** xoá/archive row `SENT` cũ định kỳ (TTL) — bảng outbox không được phình vô hạn. | Giống ledger SP7: append-only sẽ phình. Outbox row sau khi SENT + qua TTL an toàn thì xoá. |
| O6 | **Relay = Polling Publisher** (job `@Scheduled` poll PENDING) làm mặc định; **CDC (Debezium)** là nâng cấp (đọc binlog, khỏi job, thêm hạ tầng). | Polling đơn giản, tự xây, đủ cho học; CDC cùng công nghệ SP7-OLAP, để dành. |
| O7 | **Giữ thứ tự:** relay poll theo thứ tự `id`/`created_at` tăng dần; Kafka key=`userId` (đã có SP3) → cùng user vào cùng partition giữ thứ tự. | Event cùng một aggregate (user) phải đúng thứ tự (vd APPROVED trước REVOKED). |
| O8 | **Phạm vi:** áp cho kyc trước (nợ thực: `kyc.revoked`); pattern tổng quát. Nếu wallet phát event (tương lai) + multi-tenant → relay lặp tenant-schema (fleet, như SP5/SP7 purge). | Bắt đầu ở nơi nợ thật; mở rộng sau. |

---

## 3. Mô hình & luồng

```
outbox (bảng trong DB của service phát event — vd kyc)
  id           BIGINT PK ↑          (thứ tự — O7)
  aggregate    VARCHAR  (vd userId — để key Kafka, giữ thứ tự)
  topic        VARCHAR  (kyc.revoked)
  payload      JSON/TEXT
  status       ENUM(PENDING, SENT)
  created_at   TIMESTAMP            (TTL purge — O5)
  sent_at      TIMESTAMP NULL
```

```
① revoke():  @Transactional {
        save kyc_case = REVOKED
        INSERT outbox(aggregate=userId, topic=kyc.revoked, payload, PENDING)
     } commit            ← NGUYÊN TỬ (O1). KafkaPublisher KHÔNG còn gọi ở đây.

② OutboxRelay @Scheduled:
        rows = outbox WHERE status=PENDING ORDER BY id            (O7)
        for r: kafkaTemplate.send(r.topic, key=r.aggregate, r.payload)  → UPDATE r SET SENT, sent_at  (O2,O3)
        (send fail / crash giữa chừng → row PENDING → lần sau gửi lại → trùng → consumer idempotent nuốt)  (O3,O4)

③ OutboxPurge @Scheduled:  DELETE outbox WHERE status=SENT AND sent_at < now - TTL   (O5)
```

---

## 4. Đổi gì ở code hiện tại

| Chỗ | Trước | Sau |
|---|---|---|
| kyc `revoke()` | save REVOKED → `publisher.publishKycRevoked()` (dual-write) | save REVOKED **+ INSERT outbox** trong cùng tx; KHÔNG publish trực tiếp |
| `KafkaKycEventPublisher` | gọi trong luồng nghiệp vụ | dùng bởi **OutboxRelay** (đọc outbox → send) |
| wallet `KycRevokedConsumer` | idempotent (D9) | **giữ nguyên** — đã idempotent, ăn trùng OK |

→ Thay đổi gọn: chèn outbox vào tx revoke + thêm relay/purge; consumer không đụng.

---

## 5. Nợ kỹ thuật & YAGNI
- **CDC (Debezium)** thay polling — khi cần realtime/bớt job; cùng hạ tầng SP7-OLAP.
- Outbox cho **wallet** (nếu wallet phát event) → relay per-tenant-schema (fleet).
- Dead-letter cho outbox row gửi mãi không được (poison) — ghi nợ; hiện retry vô hạn + alert.
- Exactly-once (Kafka transactions / EOS) — phức tạp; at-least-once + idempotent là đủ và đơn giản hơn.

---

## 6. Chiến lược kiểm thử

```
Unit/integration (kyc, Testcontainers/H2):
  · revoke() → kyc_case=REVOKED VÀ outbox có 1 row PENDING trong CÙNG tx
  · ⭐ tx rollback (giả lỗi sau insert outbox) → KHÔNG có REVOKED VÀ KHÔNG có outbox row (nguyên tử)
  · relay: PENDING → send Kafka (EmbeddedKafka) → mark SENT
  · ⭐ relay crash giữa send & mark (giả) → row vẫn PENDING → chạy lại gửi LẠI (at-least-once)
  · purge: SENT cũ > TTL → xoá; PENDING/SENT mới → giữ
Consumer (wallet, đã có):
  · nhận kyc.revoked 2 lần (trùng do relay) → evict cache 2 lần vô hại (D9) — regression
E2E:
  · revoke → (relay đẩy) → wallet evict cache → withdraw 403; kill kyc giữa commit&publish (giả lập) → relay vẫn đẩy sau (không mất)
Regression: SP1–TraceId xanh.
```

---

## 7. Lộ trình triển khai (TDD)
1. **Bảng outbox + entity/repo** (kyc) + Flyway migration.
2. **revoke() ghi outbox trong tx** (bỏ publish trực tiếp); test nguyên tử (rollback → không cả hai).
3. **OutboxRelay** (`@Scheduled` poll PENDING → KafkaPublisher → mark SENT), giữ thứ tự id; test at-least-once (EmbeddedKafka).
4. **OutboxPurge** (`@Scheduled` xoá SENT cũ > TTL).
5. **Regression consumer** (trùng vô hại) + **e2e** (mất-event biến mất).
