package com.vng.wallet.domain;

import java.util.List;
import java.util.Optional;

/**
 * PORT — interface do tầng nghiệp vụ định nghĩa. KHÔNG nói gì về JPA/SQL.
 * Adapter ở infrastructure sẽ cài đặt nó.
 */
public interface WalletRepository {
    Wallet save(Wallet wallet);
    /** Reload theo id (không scoped) — settle/refund bởi worker/webhook/admin (không có user context). */
    Optional<Wallet> findById(Long id);
    Optional<Wallet> findByIdAndUserId(Long id, String userId);
    List<Wallet> findAllByUserId(String userId);
    List<WalletTransaction> findWithdrawalsForUserSince(String userId, java.time.Instant since);

    WalletTransaction saveTransaction(WalletTransaction transaction);
    Optional<WalletTransaction> findTransactionByIdempotencyKey(String idempotencyKey);
    /** SP6: bút toán cùng transferId + type — dùng để soi chân TRANSFER_IN (ví nhận) khi replay (TR7). */
    Optional<WalletTransaction> findTransactionByTransferIdAndType(String transferId, WalletTransaction.Type type);
    List<WalletTransaction> listTransactions(Long walletId);
}
