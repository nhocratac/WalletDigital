package com.vng.wallet.infrastructure.web;

import com.vng.wallet.application.WalletService;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.infrastructure.web.dto.CreateWalletRequest;
import com.vng.wallet.infrastructure.web.dto.MoneyRequest;
import com.vng.wallet.infrastructure.web.dto.TransactionResponse;
import com.vng.wallet.infrastructure.web.dto.WalletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * X-User-Id do api-gateway bóc từ claim JWT và ký HMAC (D1).
 * SP3: wallet TIN header này (biên tin cậy = mạng nội bộ + gateway là cửa duy nhất);
 * verify chữ ký HMAC là nợ Stage 4 đã ghi.
 */
@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@RequestHeader("X-User-Id") String userId,
                                                       @Valid @RequestBody CreateWalletRequest request) {
        Wallet wallet = walletService.createWallet(userId, request.ownerName());
        return ResponseEntity.status(HttpStatus.CREATED).body(WalletResponse.from(wallet));
    }

    @GetMapping("/{id}")
    public WalletResponse getWallet(@PathVariable Long id,
                                    @RequestHeader("X-User-Id") String userId) {
        return WalletResponse.from(walletService.getWallet(id, userId));
    }

    @PostMapping("/{id}/topup")
    public TransactionResponse topup(@PathVariable Long id,
                                     @RequestHeader("X-User-Id") String userId,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @Valid @RequestBody MoneyRequest request) {
        return TransactionResponse.from(
                walletService.topup(id, userId, request.amount(), requireIdempotencyKey(idempotencyKey)));
    }

    @PostMapping("/{id}/withdraw")
    public TransactionResponse withdraw(@PathVariable Long id,
                                        @RequestHeader("X-User-Id") String userId,
                                        @RequestHeader("Idempotency-Key") String idempotencyKey,
                                        @Valid @RequestBody MoneyRequest request) {
        return TransactionResponse.from(
                walletService.withdraw(id, userId, request.amount(), requireIdempotencyKey(idempotencyKey)));
    }

    private static String requireIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key must not be blank");
        }
        return key.trim();
    }

    @GetMapping("/{id}/transactions")
    public List<TransactionResponse> transactions(@PathVariable Long id,
                                                  @RequestHeader("X-User-Id") String userId) {
        return walletService.listTransactions(id, userId).stream().map(TransactionResponse::from).toList();
    }
}
