package com.vng.wallet;

import com.vng.wallet.dto.CreateWalletRequest;
import com.vng.wallet.dto.WalletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The Controller maps incoming HTTP requests to Java methods.
 *
 * @RestController = this class handles web requests and returns data (JSON).
 * @RequestMapping("/wallets") = every URL here starts with /wallets.
 *
 * Notice how THIN this class is: it only translates between HTTP and our
 * service. All the real logic lives in WalletService. That's intentional.
 */
@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    /**
     * POST /wallets
     * Body: { "ownerName": "Alice" }
     *
     * @Valid triggers the @NotBlank check on the request.
     * Returns HTTP 201 Created with the new wallet.
     */
    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(
            @Valid @RequestBody CreateWalletRequest request) {
        Wallet wallet = walletService.createWallet(request.ownerName());
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(WalletResponse.from(wallet));
    }

    /**
     * GET /wallets/{id}
     * Example: GET /wallets/1
     *
     * @PathVariable pulls the id out of the URL.
     * Returns HTTP 200 OK with the wallet (or 404 if not found — see the handler).
     */
    @GetMapping("/{id}")
    public WalletResponse getWallet(@PathVariable Long id) {
        Wallet wallet = walletService.getWallet(id);
        return WalletResponse.from(wallet);
    }
}
