package com.vng.wallet;

import com.vng.wallet.application.WalletService;
import com.vng.wallet.domain.IdempotencyKeyConflictException;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletTransaction;
import com.vng.wallet.support.AllowAllKycGateTestConfig;
import com.vng.wallet.support.DefaultTenantContextConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SP7 Bước 1 Task 5 (⭐ CONTRACT — chứng minh trên MySQL THẬT): sau V6, enforcement của idempotency đã
 * RỜI sổ cái → vào {@code idempotency_record}. Test chứng minh đồng thời hai điều ngược chiều:
 *
 * <ol>
 *   <li><b>Ledger SẠCH constraint:</b> INSERT thẳng hai bút toán cùng {@code idempotency_key} vào
 *       {@code wallet_transaction} — DB KHÔNG còn chặn (uk_wt_idempotency_key đã bị V6 bỏ). Đây là điều
 *       MỞ KHOÁ partition (SP7 Bước 2): MySQL error 1491 yêu cầu partition key nằm trong MỌI unique key;
 *       nay không còn unique key trên idempotency_key.</li>
 *   <li><b>Hành vi dedup KHÔNG đổi:</b> qua {@link WalletService}, cùng key vẫn replay (trả cũ), và
 *       same-key-different-payload vẫn → conflict (409/422) — vì {@code idempotency_record} chặn. Tiền
 *       bảo toàn (chỉ ghi một lần dù gọi lại).</li>
 * </ol>
 *
 * <p>Single-schema baseline (như {@link FlywaySchemaIntegrationTest}): Flyway migrate location tenant
 * (V1..V6) vào schema mặc định của container; {@code default} tenant fallback → mọi truy cập DB đi vào
 * đúng schema đó.
 */
@SpringBootTest
@Testcontainers
@Import({AllowAllKycGateTestConfig.class, DefaultTenantContextConfig.class})
class IdempotencyContractIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", () -> "root");
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
        registry.add("spring.flyway.locations", () -> "classpath:db/migration/tenant");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired
    WalletService walletService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void v6_droppedLedgerUnique_soDuplicateIdempotencyKeyIsAcceptedByLedger() {
        // INSERT THẲNG (bypass WalletService) hai bút toán cùng idempotency_key vào sổ cái.
        String key = "raw-dup-" + System.nanoTime();
        for (int i = 0; i < 2; i++) {
            jdbc.update("INSERT INTO wallet_transaction "
                            + "(wallet_id, type, amount, idempotency_key, balance_after, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    999_999L, "TOPUP", new BigDecimal("1.00"), key, new BigDecimal("1.00"), Instant.now());
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_transaction WHERE idempotency_key = ?", Integer.class, key);
        assertEquals(2, count,
                "V6 đã bỏ uk_wt_idempotency_key → sổ cái nhận hai bút toán cùng key (ledger partitionable)");
    }

    @Test
    void throughWalletService_dedupStillEnforcedByIdempotencyRecord_replayReturnsOriginal() {
        Wallet w = walletService.createWallet("user-contract-1", "Alice");
        String key = "svc-replay-" + System.nanoTime();

        WalletTransaction first = walletService.topup(w.getId(), "user-contract-1", new BigDecimal("50.00"), key);
        WalletTransaction replay = walletService.topup(w.getId(), "user-contract-1", new BigDecimal("50.00"), key);

        // Replay trả ĐÚNG bút toán cũ (cùng id) — KHÔNG ghi thêm, KHÔNG chuyển tiền hai lần.
        assertEquals(first.id(), replay.id(), "replay phải trả bút toán GỐC qua idempotency_record");
        Integer ledgerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_transaction WHERE idempotency_key = ?", Integer.class, key);
        assertEquals(1, ledgerRows, "chỉ MỘT bút toán được ghi dù topup gọi hai lần (tiền bảo toàn)");

        // Dedup được enforce ở idempotency_record (không phải ở UNIQUE sổ cái đã bỏ).
        Integer recordRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", Integer.class, key);
        assertEquals(1, recordRows, "idempotency_record là nguồn enforce dedup");
    }

    @Test
    void throughWalletService_sameKeyDifferentPayload_stillConflicts() {
        Wallet w = walletService.createWallet("user-contract-2", "Bob");
        String key = "svc-conflict-" + System.nanoTime();

        walletService.topup(w.getId(), "user-contract-2", new BigDecimal("50.00"), key);

        // Cùng key, KHÁC amount → fingerprint lệch trong idempotency_record → conflict (409/422),
        // KHÔNG ghi bút toán thứ hai.
        assertThrows(IdempotencyKeyConflictException.class, () ->
                walletService.topup(w.getId(), "user-contract-2", new BigDecimal("75.00"), key));
        Integer ledgerRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallet_transaction WHERE idempotency_key = ?", Integer.class, key);
        assertEquals(1, ledgerRows, "same-key-diff-payload bị chặn — không ghi bút toán thứ hai");
        assertTrue(ledgerRows == 1, "tiền bảo toàn dưới same-key-different-payload");
    }
}
