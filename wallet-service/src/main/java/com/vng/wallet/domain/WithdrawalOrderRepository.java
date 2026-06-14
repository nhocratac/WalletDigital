package com.vng.wallet.domain;

import java.util.List;
import java.util.Optional;

/**
 * PORT — kho lưu vòng đời lệnh rút (SP4, E6/E7). DB chính là hàng-đợi-việc-cần-làm
 * bền vững (worker quét {@link #findReconcilable(int)}).
 */
public interface WithdrawalOrderRepository {

    WithdrawalOrder save(WithdrawalOrder order);

    /** Replay tầng user (E7): Idempotency-Key -> order đã tạo. */
    Optional<WithdrawalOrder> findByIdempotencyKey(String key);

    /** Tra theo bankRef (E7 tầng wallet->bank): dùng bởi webhook/worker. */
    Optional<WithdrawalOrder> findByBankRef(String bankRef);

    /** Poll trạng thái — scoped theo chủ sở hữu (D2): order người khác -> empty. */
    Optional<WithdrawalOrder> findByIdAndUserId(Long id, String userId);

    /** Worker đọc batch order chưa-terminal (PENDING/SENT) để lái tiếp tới đích. */
    List<WithdrawalOrder> findReconcilable(int limit);
}
