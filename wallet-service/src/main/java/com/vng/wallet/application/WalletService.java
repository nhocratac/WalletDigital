package com.vng.wallet.application;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletNotFoundException;
import com.vng.wallet.domain.WalletRepository;
import org.springframework.stereotype.Service;

/**
 * USE CASES — điều phối nghiệp vụ. Phụ thuộc PORT (WalletRepository), không biết JPA.
 */
@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    public Wallet createWallet(String ownerName) {
        return walletRepository.save(Wallet.createNew(ownerName));
    }

    public Wallet getWallet(Long id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> new WalletNotFoundException(id));
    }
}
