package com.vng.wallet;

import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * The Service layer holds the BUSINESS LOGIC — the rules of what the app does.
 *
 * Why have a separate layer between the Controller and the Repository?
 *   - Controller's job: talk HTTP (read request, return response). Nothing more.
 *   - Service's job:    enforce business rules (e.g. new wallets start at 0).
 *   - Repository's job:  talk to the database.
 *
 * ARCHITECT NOTE: this is the classic 3-layer architecture
 *   Controller  ->  Service  ->  Repository
 * Each layer has ONE responsibility. When rules get complex, you'll be glad
 * they live in one predictable place instead of scattered in controllers.
 */
@Service
public class WalletService {

    private final WalletRepository walletRepository;

    // Spring automatically passes in the repository here ("dependency injection").
    // We never call `new WalletRepository()` ourselves — Spring manages that.
    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    /**
     * Business rule: every new wallet starts with a balance of zero.
     */
    public Wallet createWallet(String ownerName) {
        Wallet wallet = new Wallet(ownerName, BigDecimal.ZERO);
        return walletRepository.save(wallet);
    }

    /**
     * Find a wallet or throw a clear error if it isn't there.
     */
    public Wallet getWallet(Long id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> new WalletNotFoundException(id));
    }
}
