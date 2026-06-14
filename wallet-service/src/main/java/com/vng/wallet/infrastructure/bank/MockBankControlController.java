package com.vng.wallet.infrastructure.bank;

import com.vng.wallet.domain.BankClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Điều khiển {@link MockBankClient} cho e2e/dev — dựng kịch bản SETTLED/REJECTED/UNKNOWN trước khi
 * worker đối soát chạy. Chỉ tồn tại khi {@code wallet.bank.mock=true} (cùng điều kiện MockBankClient),
 * KHÔNG bao giờ có mặt ở cấu hình bank thật. KHÔNG phải API nghiệp vụ — chỉ là vòi điều khiển mock.
 */
@RestController
@RequestMapping("/mock-bank")
@ConditionalOnProperty(name = "wallet.bank.mock", havingValue = "true")
public class MockBankControlController {

    private final MockBankClient mockBankClient;

    public MockBankControlController(MockBankClient mockBankClient) {
        this.mockBankClient = mockBankClient;
    }

    /** Đặt kết quả mặc định cho mọi bankRef chưa cấu hình (e2e dựng happy/reject). */
    @PostMapping("/default")
    public Map<String, String> setDefault(@RequestParam("result") BankClient.BankStatus result) {
        mockBankClient.setDefaultResult(result);
        return Map.of("defaultResult", result.name());
    }
}
