package com.vng.wallet.infrastructure.web;

import com.vng.wallet.application.WalletService;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.infrastructure.web.dto.CreateWalletRequest;
import com.vng.wallet.infrastructure.web.dto.MoneyRequest;
import com.vng.wallet.infrastructure.web.dto.TransactionResponse;
import com.vng.wallet.infrastructure.web.dto.TransferRequest;
import com.vng.wallet.infrastructure.web.dto.TransferResponse;
import com.vng.wallet.infrastructure.web.dto.WalletResponse;
import com.vng.wallet.infrastructure.web.dto.WithdrawalOrderResponse;
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

    /**
     * E1: withdraw là vòng đời bất đồng bộ — trả 202 Accepted + orderId (PENDING),
     * KHÔNG còn 200 "đã xong". Tiền đã vào escrow; worker/webhook lái tiếp tới terminal.
     */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<WithdrawalOrderResponse> withdraw(@PathVariable Long id,
                                                            @RequestHeader("X-User-Id") String userId,
                                                            @RequestHeader("Idempotency-Key") String idempotencyKey,
                                                            @Valid @RequestBody MoneyRequest request) {
        WithdrawalOrderResponse body = WithdrawalOrderResponse.from(
                walletService.withdraw(id, userId, request.amount(), requireIdempotencyKey(idempotencyKey)));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
    }

    /** Poll trạng thái lệnh rút (D2 scoped) — client tra cứu vòng đời sau khi nhận 202. */
    @GetMapping("/{id}/withdrawals/{orderId}")
    public WithdrawalOrderResponse withdrawalStatus(@PathVariable Long id,
                                                    @PathVariable Long orderId,
                                                    @RequestHeader("X-User-Id") String userId) {
        return WithdrawalOrderResponse.from(walletService.getWithdrawalOrder(id, orderId, userId));
    }

    /**
     * SP6: chuyển tiền ví→ví TỨC THỜI (TR1–TR7). fromId từ path, caller từ X-User-Id, key từ
     * Idempotency-Key (thiếu -> 400 qua MissingRequestHeaderException). toWalletId + amount từ body —
     * KHÔNG đọc fromId/caller từ body (chống IDOR). Trả 200 + TransferResponse{transferId, from, to, amount}.
     */
    @PostMapping("/{fromId}/transfer")
    public TransferResponse transfer(@PathVariable Long fromId,
                                     @RequestHeader("X-User-Id") String userId,
                                     @RequestHeader("Idempotency-Key") String idempotencyKey,
                                     @Valid @RequestBody TransferRequest request) {
        return TransferResponse.from(walletService.transfer(
                fromId, request.toWalletId(), userId, request.amount(),
                requireIdempotencyKey(idempotencyKey)));
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
