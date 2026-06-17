# Thiết kế: SP7 — Ledger at Scale (sổ cái ở quy mô lớn)

- **Ngày:** 2026-06-17
- **Phạm vi:** Chiến lược mở rộng sổ cái `wallet_transaction` khi nó phình tới hàng trăm triệu+ dòng: tách idempotency, partition + archival, OLTP↔OLAP. **Forward-looking** — triển khai *khi đo được nhu cầu* (YAGNI), không phải ngay.
- **Tiền đề:** SP1–SP6 (288 test) — ledger append-only + double-entry (TRANSFER_OUT/IN), escrow withdraw, multi-tenant schema-per-tenant, idempotency UNIQUE trực tiếp trên ledger.
- **Mục tiêu học:** data-at-scale, OLTP vs OLAP, partition vs shard, idempotency-store tách rời, archival, và **đối chiếu thiết kế với best-practice ngành có trích dẫn**.

> **Nguồn gốc:** các quyết định L1–L8 do người học suy ra trong phiên Socratic, **được kiểm chứng/đính chính bằng deep-research có trích dẫn** (Stripe, Modern Treasury, TigerBeetle, MySQL docs, Percona, Brandur Leach). Mỗi quyết định ghi **mức độ tin** + nguồn. Phần "Research đính chính" liệt kê 3 điểm bản trực-giác ban đầu bị sửa.

---

## 1. Vấn đề

Ledger là **append-only, bất biến** → chỉ tăng, không xóa → phình vô hạn. Hai áp lực:
- **Ghi:** vẫn nhanh (append vào cuối, PK tăng dần, ít index/không FK) — *không* phải vấn đề.
- **Đọc analytics/report:** quét-gộp hàng trăm triệu dòng → nếu chạy trên DB vận hành sẽ **cạnh tranh** traffic live → vấn đề thật.

Mục tiêu SP7: giữ ghi nhanh + cô lập tài nguyên + cho phép archive + phục vụ report — **mà không** phá idempotency hay traffic live.

---

## 2. Bảng quyết định (L1–L8) — kèm mức tin & nguồn

| # | Quyết định | Tin | Nguồn / ghi chú |
|---|---|---|---|
| L1 | Giữ ledger **append-only, bất biến, double-entry**; balance **DẪN XUẤT** từ chuỗi bút toán (`balance_after` mỗi dòng = snapshot-per-row để khỏi replay). | **Cao** | Stripe ledger blog; TigerBeetle data-modeling; Modern Treasury. Balance là "phép biến đổi trên log bất biến". |
| L2 | **Tách `idempotency_key` ra một store RIÊNG, KHÔNG partition, TTL ngắn** (purge sau vài ngày retry). Làm việc này **TRƯỚC** khi partition ledger. | **TB** (logic + Stripe hậu thuẫn; có phản ví dụ) | Luật MySQL ép tách (xem L3); Stripe/Brandur: idempotency store riêng + capture response + TTL. ⚠️ **Phản ví dụ:** TigerBeetle dùng `id` **inline** — nhưng nó là DB chuyên dụng *không* partition SQL; với MySQL+partition thì phải tách. |
| L3 | **Time-RANGE partition `wallet_transaction` theo `created_at` (tháng)** — CHỈ khi đo được phình gây đau. | **Cao** (cơ chế) | MySQL docs. ⚠️ Luật cứng: *partition key phải nằm trong MỌI unique key* (error 1491) → đó là lý do L2. ⚠️ Pruning chỉ hoạt động khi **query kèm `created_at`**. |
| L4 | **Archive bằng `EXCHANGE PARTITION` → bảng riêng (cold storage) RỒI mới `DROP`.** Không `DROP` trần (mất dữ liệu). | **Cao** | MySQL partitioning-management. *(điểm research bổ sung — bản trực giác ban đầu chỉ nói "drop mảnh cũ".)* |
| L5 | **KHÔNG partition bảng vận hành bounded** (`withdrawal_order` — query theo `state`, không theo thời gian → partition làm chậm hơn). | **Cao** | Percona "partitioning can save you or kill you": query không kèm partition key → quét hết mảnh → chậm. |
| L6 | **Tách OLTP ↔ OLAP:** report chạy trên **warehouse columnar** (BigQuery/Snowflake/ClickHouse), nạp từ ledger bằng **batch ETL HOẶC CDC**; pre-aggregate rollup; KHÔNG quét analytics trên DB vận hành. | **Cao** (tách); **TB** (CDC vs batch) | Stripe/MT/Uber data infra. ⚠️ **CDC không phải mặc định ngành** — Modern Treasury dùng **batch sync**; CDC (Debezium) chỉ là *một* lựa chọn. |
| L7 | **PK:** auto-increment đã đủ tốt (insert ở rìa phải = write-locality tốt cho InnoDB). **Snowflake/time-ordered ID chỉ khi cần sharding / sinh ID phân tán.** | **TB** (research hạ cấp) | ID-strategy refs. *(bản trực giác ban đầu nghiêng Snowflake-mặc-định — research KHÔNG củng cố; hạ xuống "tùy chọn khi shard".)* |
| L8 | **Tương tác SP5 (multi-tenant):** partition + idempotency-store + archival chạy **per-tenant-schema** (fleet, như fleet-migration). Quyết định per-tenant vs shared cho dedup/OLAP để mở. | **Mở** | open question — chưa có consensus rõ. |

---

## 3. Research đính chính (3 điểm bản trực-giác ban đầu sai/quá đà)

1. **Bỏ con số "~100 giao dịch/giây mỗi account"** — claim này **BỊ BÁC** trong verify (vote 1-2). Không trích như trần cứng. *(Modern Treasury part IV.)*
2. **Snowflake ID KHÔNG bắt buộc** — research không củng cố Snowflake-as-PK; auto-increment vốn tốt cho single-node. → L7 hạ Snowflake xuống "khi sharding".
3. **CDC không phải "the way"** — Modern Treasury dùng **batch**; → L6 nêu cả batch lẫn CDC.
4. **(bổ sung)** `EXCHANGE PARTITION` trước `DROP` — research thêm bước archive đúng (L4).

---

## 4. Kiến trúc đích (khi triển khai đầy đủ)

```
                      ┌─────────────────────────────────────────────┐
   ghi tiền (live) ──►│ OLTP: MySQL (per-tenant schema)              │
                      │  wallet_transaction  ← RANGE partition /tháng│──► EXCHANGE+DROP
                      │     (KHÔNG còn UNIQUE idempotency_key)        │     mảnh cũ → cold storage
                      │  idempotency_record  ← bảng RIÊNG, no-part,   │
                      │     UNIQUE(key) toàn cục, TTL ngắn, purge     │
                      │  withdrawal_order    ← KHÔNG partition        │
                      └───────────────┬─────────────────────────────┘
                                      │ batch ETL / CDC (Debezium→Kafka)
                                      ▼
                      ┌─────────────────────────────────────────────┐
   report/analytics ─►│ OLAP: warehouse columnar (BigQuery/...)       │
                      │  + rollup tables (doanh thu theo ngày/tenant) │
                      └─────────────────────────────────────────────┘
```

Luồng idempotency mới (L2): trước khi ghi ledger → `INSERT idempotency_record(key)` (UNIQUE bắt trùng) → trùng thì replay; không thì ghi 2 bút toán double-entry vào ledger (đã sạch constraint → partition được).

---

## 5. Thứ tự triển khai (khi đo được nhu cầu — staged, YAGNI)

```
Bước 0 (đo): theo dõi kích thước bảng, p99 query, thời gian backup. CHƯA làm gì tới khi đau thật.
Bước 1: Tách idempotency → idempotency_record (no-part, TTL). expand/contract:
        dual-write key sang bảng mới → backfill → chuyển đọc → bỏ UNIQUE khỏi ledger.
        (làm trước, rẻ, đúng kể cả không partition.)
Bước 2: Partition wallet_transaction theo created_at (đã sạch constraint sau Bước 1).
        Job tạo partition tháng tới + EXCHANGE/DROP mảnh quá hạn — CHẠY CHO MỌI tenant schema (fleet).
Bước 3: Pipeline OLTP→OLAP (batch trước; CDC nếu cần realtime) + rollup tables. Report chuyển sang OLAP.
Bước 4 (nếu một máy không gánh nổi): shard theo tenant/hash — sau partition.
```

> Bài học YAGNI: **tách idempotency (Bước 1) là việc đáng làm sớm** (đúng thiết kế dù lớn hay nhỏ); partition/OLAP để dành khi số liệu đòi. Đừng partition sớm — thêm job maintenance fleet + bẫy "query thiếu partition key" (Percona).

---

## 6. Câu hỏi mở (quyết định SA khi làm thật)
- TTL/cadence purge `idempotency_record` (vài giờ? vài ngày? theo SLA retry của client).
- Dedup & OLAP: **per-tenant** (cô lập, nhiều store) hay **shared** (gọn, nhưng trộn tenant)? — đụng SP5.
- Snowflake ID: chỉ khi shard? đo write-hotspot auto-increment trước.
- CDC (Debezium→Kafka) vs batch ETL: realtime cần tới đâu.
- Quy trình `EXCHANGE PARTITION` → cold storage (S3/Glacier?) cụ thể + retention compliance.

---

## 7. Nguồn (deep-research, đã verify đối kháng)
- Stripe — Ledger: system for tracking & validating money movement (primary).
- TigerBeetle — data modeling / system architecture (primary).
- Modern Treasury — How to scale a ledger (parts I, IV, V), Accounting for developers (primary).
- MySQL docs — partitioning limitations (keys vs unique keys, error 1491), partitioning management (primary).
- Percona — "MySQL partitioning can save you or kill you" (contrarian).
- Brandur Leach — idempotency-keys (Stripe) (blog).
- Uber data infrastructure (secondary).

> ⚠️ Lưu ý độ tin: phần ledger/MySQL-rule/OLTP-OLAP **tin cao** (nguồn primary). Phần tách-idempotency-table **suy luận + Stripe hậu thuẫn** (có phản ví dụ TigerBeetle inline). Snowflake & partition-vs-shard **không có claim sống sót** → để mở.
