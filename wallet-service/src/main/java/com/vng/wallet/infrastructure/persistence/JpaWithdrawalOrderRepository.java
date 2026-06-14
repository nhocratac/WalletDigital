package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.domain.WithdrawalOrderRepository;
import com.vng.wallet.domain.WithdrawalState;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * ADAPTER — cài PORT {@link WithdrawalOrderRepository} bằng JPA. Mapping do MapStruct sinh.
 */
@Repository
public class JpaWithdrawalOrderRepository implements WithdrawalOrderRepository {

    private static final List<WithdrawalState> RECONCILABLE =
            List.of(WithdrawalState.PENDING, WithdrawalState.SENT);

    private final SpringDataWithdrawalOrderJpa jpa;
    private final WithdrawalOrderMapper mapper;

    public JpaWithdrawalOrderRepository(SpringDataWithdrawalOrderJpa jpa, WithdrawalOrderMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public WithdrawalOrder save(WithdrawalOrder order) {
        return mapper.toDomain(jpa.save(mapper.toEntity(order)));
    }

    @Override
    public Optional<WithdrawalOrder> findByIdempotencyKey(String key) {
        return jpa.findByIdempotencyKey(key).map(mapper::toDomain);
    }

    @Override
    public Optional<WithdrawalOrder> findByBankRef(String bankRef) {
        return jpa.findByBankRef(bankRef).map(mapper::toDomain);
    }

    @Override
    public Optional<WithdrawalOrder> findByIdAndUserId(Long id, String userId) {
        return jpa.findByIdAndUserId(id, userId).map(mapper::toDomain);
    }

    @Override
    public List<WithdrawalOrder> findReconcilable(int limit) {
        return jpa.findByStateIn(RECONCILABLE, PageRequest.of(0, limit))
                .stream().map(mapper::toDomain).toList();
    }
}
