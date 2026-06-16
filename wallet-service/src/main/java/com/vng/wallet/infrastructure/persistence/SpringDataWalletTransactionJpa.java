package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.WalletTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SpringDataWalletTransactionJpa extends JpaRepository<WalletTransactionEntity, Long> {
    Optional<WalletTransactionEntity> findByIdempotencyKey(String idempotencyKey);
    Optional<WalletTransactionEntity> findByTransferIdAndType(String transferId, WalletTransaction.Type type);
    List<WalletTransactionEntity> findByWalletIdOrderByCreatedAtAsc(Long walletId);
    List<WalletTransactionEntity> findByWalletIdInAndTypeAndCreatedAtGreaterThanEqual(
            List<Long> walletIds, WalletTransaction.Type type, Instant since);
}
