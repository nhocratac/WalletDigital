package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * ADAPTER — cài đặt PORT domain bằng JPA. Mapping entity<->domain do MapStruct
 * sinh lúc compile (WalletMapper). Lõi nghiệp vụ không thấy JPA.
 */
@Repository
public class JpaWalletRepository implements WalletRepository {

    private final SpringDataWalletJpa jpa;
    private final WalletMapper mapper;

    public JpaWalletRepository(SpringDataWalletJpa jpa, WalletMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public Wallet save(Wallet wallet) {
        return mapper.toDomain(jpa.save(mapper.toEntity(wallet)));
    }

    @Override
    public Optional<Wallet> findById(Long id) {
        return jpa.findById(id).map(mapper::toDomain);
    }

    @Override
    public WalletTransaction saveTransaction(WalletTransaction transaction) {
        throw new UnsupportedOperationException("Task 5");
    }

    @Override
    public Optional<WalletTransaction> findTransactionByIdempotencyKey(String idempotencyKey) {
        throw new UnsupportedOperationException("Task 5");
    }

    @Override
    public List<WalletTransaction> listTransactions(Long walletId) {
        throw new UnsupportedOperationException("Task 5");
    }
}
