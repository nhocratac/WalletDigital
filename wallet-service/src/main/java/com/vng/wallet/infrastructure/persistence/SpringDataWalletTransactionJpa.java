package com.vng.wallet.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataWalletTransactionJpa extends JpaRepository<WalletTransactionEntity, Long> {
    Optional<WalletTransactionEntity> findByIdempotencyKey(String idempotencyKey);
    List<WalletTransactionEntity> findByWalletIdOrderByCreatedAtAsc(Long walletId);
}
