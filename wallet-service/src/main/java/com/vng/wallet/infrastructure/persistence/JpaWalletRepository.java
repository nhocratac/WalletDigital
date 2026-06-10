package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * ADAPTER — cài đặt PORT domain bằng JPA. Đây là "cầu nối" map giữa
 * domain Wallet (thuần) và WalletEntity (JPA). Lõi nghiệp vụ không thấy JPA.
 */
@Repository
public class JpaWalletRepository implements WalletRepository {

    private final SpringDataWalletJpa jpa;

    public JpaWalletRepository(SpringDataWalletJpa jpa) {
        this.jpa = jpa;
    }

    @Override
    public Wallet save(Wallet wallet) {
        WalletEntity entity = new WalletEntity(wallet.getId(), wallet.getOwnerName(), wallet.getBalance());
        return toDomain(jpa.save(entity));
    }

    @Override
    public Optional<Wallet> findById(Long id) {
        return jpa.findById(id).map(this::toDomain);
    }

    private Wallet toDomain(WalletEntity e) {
        return new Wallet(e.getId(), e.getOwnerName(), e.getBalance());
    }
}
