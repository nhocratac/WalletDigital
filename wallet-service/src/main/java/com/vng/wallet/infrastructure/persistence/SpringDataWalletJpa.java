package com.vng.wallet.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data sinh sẵn save/findById cho WalletEntity. */
public interface SpringDataWalletJpa extends JpaRepository<WalletEntity, Long> {
}
