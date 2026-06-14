package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.WithdrawalState;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

/** Spring Data sinh save/findById; query tùy chỉnh cho replay + reconcile. */
public interface SpringDataWithdrawalOrderJpa extends JpaRepository<WithdrawalOrderEntity, Long> {

    Optional<WithdrawalOrderEntity> findByIdempotencyKey(String idempotencyKey);

    Optional<WithdrawalOrderEntity> findByBankRef(String bankRef);

    Optional<WithdrawalOrderEntity> findByIdAndUserId(Long id, String userId);

    // Worker đọc các order chưa-terminal; ORDER BY updatedAt ~ id (FIFO ổn định) + giới hạn batch.
    @Query("SELECT o FROM WithdrawalOrderEntity o WHERE o.state IN :states ORDER BY o.id ASC")
    List<WithdrawalOrderEntity> findByStateIn(@Param("states") List<WithdrawalState> states, Pageable pageable);
}
