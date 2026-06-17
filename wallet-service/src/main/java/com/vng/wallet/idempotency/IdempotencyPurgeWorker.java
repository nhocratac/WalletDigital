package com.vng.wallet.idempotency;

import com.vng.wallet.tenancy.TenantContext;
import com.vng.wallet.tenancy.master.TenantRegistry;
import com.vng.wallet.tenancy.master.TenantRegistryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * SP7 Bước 1 Task 6 (L2): TTL purge cho {@code idempotency_record} — giữ bảng record KHÔNG phình.
 * Key đã quá hạn TTL (mặc định 7 ngày) thì client thực tế không còn retry → xoá an toàn (replay chỉ
 * có ý nghĩa trong cửa sổ retry ngắn). Bảng record là nguồn enforce dedup duy nhất (sau Task 5) nên
 * purge phải CHẬM HƠN cửa sổ retry của client — TTL config được, KHÔNG xoá record còn "nóng".
 *
 * <p>Chạy trên thread {@code @Scheduled} (KHÔNG có request/filter → không nhận tenant từ header), nên
 * NÓ tự lặp tenant registry + {@code set/clear} {@link TenantContext} cho từng tenant (purge record
 * trong ĐÚNG schema từng tenant — {@code idempotency_record} là per-tenant-schema, routed như mọi
 * repo khác, SP5). Tái dùng nguyên pattern của reconciliation worker (T9):
 * <ul>
 *   <li><b>set/clear in finally per tenant (T4):</b> thread pool dùng lại không rò context sang tenant kế.</li>
 *   <li><b>failure isolation:</b> một tenant lỗi KHÔNG chặn vòng — log rồi tenant kế vẫn purge.</li>
 *   <li><b>baseline fallback:</b> registry rỗng (single-schema baseline) → purge schema hiện hữu.</li>
 * </ul>
 *
 * <p>Bật bằng {@code wallet.idempotency.purge.enabled=true} (mặc định TẮT — test/dev cũ không chạy
 * thread nền). TTL qua {@code wallet.idempotency.ttl-days} (mặc định 7).
 */
@Component
@ConditionalOnProperty(name = "wallet.idempotency.purge.enabled", havingValue = "true")
public class IdempotencyPurgeWorker {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeWorker.class);

    private final IdempotencyStore store;
    private final TenantRegistryRepository registryRepository;
    private final int ttlDays;

    public IdempotencyPurgeWorker(IdempotencyStore store,
                                  TenantRegistryRepository registryRepository,
                                  @Value("${wallet.idempotency.ttl-days:7}") int ttlDays) {
        this.store = store;
        this.registryRepository = registryRepository;
        this.ttlDays = ttlDays;
    }

    @Scheduled(fixedDelayString = "${wallet.idempotency.purge.interval-ms:3600000}")
    public void run() {
        try {
            runOnce(Instant.now());
        } catch (Exception e) {
            // Một vòng lỗi không được giết scheduler — log rồi vòng sau chạy lại.
            log.warn("idempotency purge round failed (will retry): {}", e.toString());
        }
    }

    /**
     * Một vòng purge across mọi tenant ACTIVE (hoặc baseline single-schema nếu registry rỗng). Record
     * {@code created_at < now - ttlDays} bị xoá; record mới hơn TTL giữ lại. {@code now} truyền vào để
     * test điều khiển được mốc thời gian.
     */
    public void runOnce(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(ttlDays));

        List<TenantRegistry> active;
        try {
            active = registryRepository.findByStatus(TenantRegistry.Status.ACTIVE);
        } catch (Exception e) {
            // Registry unreadable: trong PROD là lỗi thật → rethrow (vòng log surface). Trong baseline
            // single-schema (có default tenant) thì coi như "no tenants" và purge schema đó.
            if (TenantContext.effective() == null) {
                throw e;
            }
            active = Collections.emptyList();
        }

        if (active.isEmpty()) {
            int purged = store.deleteOlderThan(cutoff);
            log.debug("idempotency purge (baseline): removed {} record(s) older than {}", purged, cutoff);
            return;
        }

        for (TenantRegistry tenant : active) {
            try {
                TenantContext.set(tenant.getTenantId());
                int purged = store.deleteOlderThan(cutoff);
                log.debug("idempotency purge tenant={}: removed {} record(s) older than {}",
                        tenant.getTenantId(), purged, cutoff);
            } catch (Exception e) {
                // Một tenant lỗi không chặn fleet — log + tiếp (vòng sau thử lại).
                log.warn("idempotency purge failed for tenant={} (will retry next round): {}",
                        tenant.getTenantId(), e.toString());
            } finally {
                TenantContext.clear(); // T4 — không rò tenant này sang vòng/tenant kế.
            }
        }
    }
}
