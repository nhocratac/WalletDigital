package com.vng.wallet.domain;

import java.math.BigDecimal;

/**
 * PORT — ngân hàng là dependency NGOÀI (SP4, E5/E8). Cú gọi nằm NGOÀI transaction DB.
 *
 * <p>"unknown != failed" (E9): timeout/5xx/breaker-mở -> {@link BankStatus#UNKNOWN},
 * KHÔNG tự suy ra REJECTED — chỉ REJECTED dứt khoát mới được refund.
 *
 * <p>Idempotency tầng wallet->bank (E7): mọi lần {@link #transfer}/{@link #status} dùng LẠI
 * cùng {@code bankRef} (sinh ở bước ①) — bank tự khử trùng, không trả kép sau crash/retry.
 */
public interface BankClient {

    /** ② chuyển tiền tới bank (idempotent theo bankRef). */
    TransferAck transfer(String bankRef, BigDecimal amount);

    /** E8: query trạng thái một lệnh đã gửi (worker dùng sau crash, dùng LẠI bankRef). */
    BankStatus status(String bankRef);

    enum BankStatus { SETTLED, REJECTED, UNKNOWN }

    record TransferAck(BankStatus result) {}
}
