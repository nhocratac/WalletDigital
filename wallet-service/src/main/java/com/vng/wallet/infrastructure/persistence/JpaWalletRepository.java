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
    private final SpringDataWalletTransactionJpa txJpa;
    private final WalletMapper mapper;

    public JpaWalletRepository(SpringDataWalletJpa jpa, SpringDataWalletTransactionJpa txJpa, WalletMapper mapper) {
        this.jpa = jpa;
        this.txJpa = txJpa;
        this.mapper = mapper;
    }

    @Override
    public Wallet save(Wallet wallet) {
        return mapper.toDomain(jpa.save(mapper.toEntity(wallet)));
    }

    @Override
    public Optional<Wallet> findByIdAndUserId(Long id, String userId) {
        return jpa.findByIdAndUserId(id, userId).map(mapper::toDomain);
    }

    @Override
    public Optional<Wallet> findById(Long id) {
        return jpa.findById(id).map(mapper::toDomain);
    }

    @Override
    public List<Wallet> findAllByUserId(String userId) {
        return jpa.findAllByUserId(userId).stream().map(mapper::toDomain).toList();
    }

    @Override
    public List<WalletTransaction> findWithdrawalsForUserSince(String userId, java.time.Instant since) {
        List<Long> ids = jpa.findAllByUserId(userId).stream().map(WalletEntity::getId).toList();
        if (ids.isEmpty()) return List.of();
        // SP4: bút toán mở đầu một lệnh rút giờ là WITHDRAW_HOLD (bước ①) — đây là dấu vết "đã rút sau revoke".
        return txJpa.findByWalletIdInAndTypeAndCreatedAtGreaterThanEqual(ids, WalletTransaction.Type.WITHDRAW_HOLD, since)
                .stream().map(mapper::toDomain).toList();
    }

    @Override
    public WalletTransaction saveTransaction(WalletTransaction transaction) {
        return mapper.toDomain(txJpa.save(mapper.toEntity(transaction)));
    }

    @Override
    public Optional<WalletTransaction> findTransactionByIdempotencyKey(String idempotencyKey) {
        return txJpa.findByIdempotencyKey(idempotencyKey).map(mapper::toDomain);
    }

    @Override
    public List<WalletTransaction> listTransactions(Long walletId) {
        return txJpa.findByWalletIdOrderByCreatedAtAsc(walletId).stream().map(mapper::toDomain).toList();
    }
}
