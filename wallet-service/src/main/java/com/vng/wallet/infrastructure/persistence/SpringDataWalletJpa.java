package com.vng.wallet.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data sinh sẵn save/findById cho WalletEntity. */
public interface SpringDataWalletJpa extends JpaRepository<WalletEntity, Long> {
    Optional<WalletEntity> findByIdAndUserId(Long id, String userId);
    List<WalletEntity> findAllByUserId(String userId);
}
