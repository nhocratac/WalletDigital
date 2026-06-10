package com.vng.wallet.infrastructure.web;

import com.vng.wallet.application.WalletService;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.infrastructure.web.dto.CreateWalletRequest;
import com.vng.wallet.infrastructure.web.dto.WalletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        Wallet wallet = walletService.createWallet(request.ownerName());
        return ResponseEntity.status(HttpStatus.CREATED).body(WalletResponse.from(wallet));
    }

    @GetMapping("/{id}")
    public WalletResponse getWallet(@PathVariable Long id) {
        return WalletResponse.from(walletService.getWallet(id));
    }
}
