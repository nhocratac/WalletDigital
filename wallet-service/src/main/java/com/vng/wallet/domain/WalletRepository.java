package com.vng.wallet.domain;

import java.util.Optional;

/**
 * PORT — interface do tầng nghiệp vụ định nghĩa. KHÔNG nói gì về JPA/SQL.
 * Adapter ở infrastructure sẽ cài đặt nó.
 */
public interface WalletRepository {
    Wallet save(Wallet wallet);
    Optional<Wallet> findById(Long id);
}
