package com.vng.wallet.domain;

import java.util.List;
import java.util.Optional;

/**
 * PORT — interface do tầng nghiệp vụ định nghĩa. KHÔNG nói gì về JPA/SQL.
 * Adapter ở infrastructure sẽ cài đặt nó.
 */
public interface WalletRepository {
    Wallet save(Wallet wallet);
    Optional<Wallet> findById(Long id);

    WalletTransaction saveTransaction(WalletTransaction transaction);
    Optional<WalletTransaction> findTransactionByIdempotencyKey(String idempotencyKey);
    List<WalletTransaction> listTransactions(Long walletId);
}
