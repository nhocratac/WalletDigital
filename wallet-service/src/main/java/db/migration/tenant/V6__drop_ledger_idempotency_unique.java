package db.migration.tenant;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

/**
 * SP7 Bước 1 Task 5 (CONTRACT pha): bỏ {@code UNIQUE(idempotency_key)} khỏi sổ cái sau khi
 * {@code idempotency_record} đã là NGUỒN enforce dedup duy nhất (Task 3/4 đã đấu cả 3 đường
 * topup/withdraw/transfer qua reserve-key-FIRST). Sau migration này, ledger SẠCH constraint trên
 * {@code idempotency_key} → SP7 Bước 2 (RANGE partition theo {@code created_at}) khả thi
 * (MySQL error 1491: partition key phải nằm trong MỌI unique key — nay không còn unique key đó nữa).
 *
 * <p>Cột {@code idempotency_key} được GIỮ LẠI làm metadata/đối soát và để dual-write phục vụ replay
 * (WalletService nạp lại bút toán winner qua inline key) — chỉ ràng buộc UNIQUE bị bỏ.
 *
 * <p>Bỏ:
 * <ul>
 *   <li>{@code uk_wt_idempotency_key} trên {@code wallet_transaction} (V2)</li>
 *   <li>{@code uk_wo_idempotency_key} trên {@code withdrawal_order} (V3) —
 *       {@code uk_wo_bank_ref} GIỮ NGUYÊN (bank_ref vẫn phải UNIQUE, E7).</li>
 * </ul>
 *
 * <p>Vì sao Java migration thay vì SQL thuần: cú pháp bỏ một UNIQUE constraint có TÊN khác nhau giữa
 * H2 (slice test) và MySQL (integration/prod):
 * <ul>
 *   <li>H2 2.x: {@code ALTER TABLE t DROP CONSTRAINT name}</li>
 *   <li>MySQL 8: {@code ALTER TABLE t DROP INDEX name} (DROP CONSTRAINT không áp cho UNIQUE)</li>
 * </ul>
 * Một file SQL không cover được cả hai một cách portable, nên migration này nhận biết dialect qua
 * {@code DatabaseMetaData.getDatabaseProductName()} rồi phát đúng câu lệnh. Idempotent ở mức an toàn:
 * dùng {@code DROP CONSTRAINT IF EXISTS} (H2) và bắt lỗi "không tồn tại" cho MySQL nếu chạy lại tay.
 */
public class V6__drop_ledger_idempotency_unique extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        // BƯỚC 1 — BACKFILL (one-time, per tenant schema, idempotent): copy key cũ từ sổ cái sang
        // idempotency_record TRƯỚC khi bỏ UNIQUE. Money-safety: nếu bỏ UNIQUE mà CHƯA backfill thì
        // một replay của key CŨ (tạo trước SP7) sẽ không tìm thấy record -> chạy lại money op (chuyển
        // tiền hai lần). Fingerprint tính GIỐNG HỆT IdempotencyService.fingerprintOf để replay payload
        // khớp -> trả cũ; lệch -> 409. INSERT bỏ qua null/đã có (idempotent, chạy lại được).
        backfillFromWalletTransaction(connection);
        backfillFromWithdrawalOrder(connection);

        // BƯỚC 2 — CONTRACT: bỏ UNIQUE khỏi sổ cái (idempotency_record giờ là nguồn enforce).
        String product = connection.getMetaData().getDatabaseProductName();
        boolean isMysql = product != null && product.toLowerCase().contains("mysql");
        try (Statement st = connection.createStatement()) {
            if (isMysql) {
                st.execute("ALTER TABLE wallet_transaction DROP INDEX uk_wt_idempotency_key");
                st.execute("ALTER TABLE withdrawal_order DROP INDEX uk_wo_idempotency_key");
            } else {
                // H2 (slice tests) + các DB ANSI hỗ trợ DROP CONSTRAINT.
                st.execute("ALTER TABLE wallet_transaction DROP CONSTRAINT IF EXISTS uk_wt_idempotency_key");
                st.execute("ALTER TABLE withdrawal_order DROP CONSTRAINT IF EXISTS uk_wo_idempotency_key");
            }
        }
    }

    /**
     * Backfill TOPUP + TRANSFER_OUT từ {@code wallet_transaction}. Chỉ chân mang idempotency_key
     * (TOPUP/TRANSFER_OUT — chân TRANSFER_IN/WITHDRAW_HOLD không phải nguồn key cho đường nghiệp vụ
     * tương ứng). operation_type = {@code type.name()} (TOPUP / TRANSFER_OUT) để khớp opType mà
     * WalletService dùng khi reserve. result_ref = id giao dịch (topup) / transfer_id (transfer).
     */
    private void backfillFromWalletTransaction(Connection connection) throws Exception {
        String select = "SELECT id, wallet_id, type, amount, idempotency_key, transfer_id "
                + "FROM wallet_transaction WHERE idempotency_key IS NOT NULL "
                + "AND type IN ('TOPUP', 'TRANSFER_OUT')";
        try (PreparedStatement ps = connection.prepareStatement(select);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String type = rs.getString("type");
                long walletId = rs.getLong("wallet_id");
                BigDecimal amount = rs.getBigDecimal("amount");
                String key = rs.getString("idempotency_key");
                String fingerprint;
                String resultRef;
                if ("TRANSFER_OUT".equals(type)) {
                    // fingerprint của transfer gồm (fromId, toId, amount) — toId nằm ở chân IN cùng transfer_id.
                    String transferId = rs.getString("transfer_id");
                    Long toId = receiverWalletId(connection, transferId);
                    if (toId == null) {
                        continue; // dữ liệu không nhất quán (thiếu chân IN) -> bỏ qua, không đoán
                    }
                    fingerprint = fingerprintOf("TRANSFER_OUT",
                            String.valueOf(walletId), String.valueOf(toId), amount.toPlainString());
                    resultRef = transferId;
                } else { // TOPUP
                    fingerprint = fingerprintOf("TOPUP", String.valueOf(walletId), amount.toPlainString());
                    resultRef = String.valueOf(rs.getLong("id"));
                }
                insertRecordIfAbsent(connection, key, type, fingerprint, resultRef);
            }
        }
    }

    /**
     * Backfill WITHDRAW từ {@code withdrawal_order}. operation_type = WITHDRAW; fingerprint
     * (WITHDRAW, walletId, amount) — khớp {@code WalletService.withdrawFingerprint}. result_ref = orderId.
     */
    private void backfillFromWithdrawalOrder(Connection connection) throws Exception {
        String select = "SELECT id, wallet_id, amount, idempotency_key "
                + "FROM withdrawal_order WHERE idempotency_key IS NOT NULL";
        try (PreparedStatement ps = connection.prepareStatement(select);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long walletId = rs.getLong("wallet_id");
                BigDecimal amount = rs.getBigDecimal("amount");
                String key = rs.getString("idempotency_key");
                String fingerprint = fingerprintOf("WITHDRAW",
                        String.valueOf(walletId), amount.toPlainString());
                insertRecordIfAbsent(connection, key, "WITHDRAW", fingerprint, String.valueOf(rs.getLong("id")));
            }
        }
    }

    private Long receiverWalletId(Connection connection, String transferId) throws Exception {
        if (transferId == null) {
            return null;
        }
        String sql = "SELECT wallet_id FROM wallet_transaction "
                + "WHERE transfer_id = ? AND type = 'TRANSFER_IN'";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong("wallet_id") : null;
            }
        }
    }

    /** INSERT vào idempotency_record nếu key chưa có (idempotent — chạy lại migration không nhân đôi). */
    private void insertRecordIfAbsent(Connection connection, String key, String opType,
                                      String fingerprint, String resultRef) throws Exception {
        try (PreparedStatement check = connection.prepareStatement(
                "SELECT 1 FROM idempotency_record WHERE idempotency_key = ?")) {
            check.setString(1, key);
            try (ResultSet rs = check.executeQuery()) {
                if (rs.next()) {
                    return; // đã có (record do Task 3/4 tạo, hoặc lần chạy backfill trước) -> bỏ qua
                }
            }
        }
        String insert = "INSERT INTO idempotency_record "
                + "(idempotency_key, operation_type, request_fingerprint, result_ref, created_at) "
                + "VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement ps = connection.prepareStatement(insert)) {
            ps.setString(1, key);
            ps.setString(2, opType);
            ps.setString(3, fingerprint);
            ps.setString(4, resultRef);
            ps.setTimestamp(5, Timestamp.from(Instant.now()));
            ps.executeUpdate();
        }
    }

    /** GIỐNG HỆT {@code IdempotencyService.fingerprintOf}: SHA-256 hex của opType + parts nối bằng '|'. */
    private String fingerprintOf(String operationType, String... payloadParts) throws Exception {
        StringBuilder sb = new StringBuilder(operationType);
        for (String part : payloadParts) {
            sb.append('|').append(part);
        }
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(sb.toString().getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
