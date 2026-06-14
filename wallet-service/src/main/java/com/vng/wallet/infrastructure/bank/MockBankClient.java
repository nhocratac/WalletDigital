package com.vng.wallet.infrastructure.bank;

import com.vng.wallet.domain.BankClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bank giả lập (dev/test, e2e) — kết quả cấu hình được theo bankRef. KHÔNG gọi mạng.
 * Bật bằng {@code wallet.bank.mock=true}. Mặc định mọi bankRef chưa cấu hình -> SETTLED.
 *
 * <p>{@link #transfer} idempotent theo bankRef: nhớ kết quả lần đầu, lần sau trả LẠI cùng kết quả
 * (mô phỏng bank thật khử trùng — chống trả kép sau crash/retry, E7).
 */
@Component
@ConditionalOnProperty(name = "wallet.bank.mock", havingValue = "true")
public class MockBankClient implements BankClient {

    private final Map<String, BankStatus> scenarios = new ConcurrentHashMap<>();
    private final Map<String, BankStatus> settledRefs = new ConcurrentHashMap<>();
    private volatile BankStatus defaultResult = BankStatus.SETTLED;

    /** Cấu hình kịch bản cho một bankRef (e2e/dev/test dựng SETTLED/REJECTED/UNKNOWN). */
    public void configure(String bankRef, BankStatus result) {
        scenarios.put(bankRef, result);
    }

    public void setDefaultResult(BankStatus result) {
        this.defaultResult = result;
    }

    @Override
    public TransferAck transfer(String bankRef, BigDecimal amount) {
        BankStatus result = scenarios.getOrDefault(bankRef, defaultResult);
        if (result != BankStatus.UNKNOWN) {
            settledRefs.put(bankRef, result); // ghi nhớ -> status() trả nhất quán, idempotent
        }
        return new TransferAck(result);
    }

    @Override
    public BankStatus status(String bankRef) {
        BankStatus known = settledRefs.get(bankRef);
        if (known != null) {
            return known;
        }
        return scenarios.getOrDefault(bankRef, BankStatus.UNKNOWN); // chưa thấy -> UNKNOWN
    }
}
