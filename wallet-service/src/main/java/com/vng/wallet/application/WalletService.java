package com.vng.wallet.application;

import com.vng.wallet.domain.IdempotencyKeyConflictException;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletNotFoundException;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * USE CASES — điều phối nghiệp vụ. Phụ thuộc PORT (WalletRepository), không biết JPA.
 */
@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public Wallet createWallet(String ownerName) {
        return walletRepository.save(Wallet.createNew(ownerName));
    }

    @Transactional(readOnly = true)
    public Wallet getWallet(Long id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> new WalletNotFoundException(id));
    }

    @Transactional
    public WalletTransaction topup(Long walletId, BigDecimal amount, String idempotencyKey) {
        return applyMoneyOperation(walletId, amount, idempotencyKey, WalletTransaction.Type.TOPUP);
    }

    @Transactional
    public WalletTransaction withdraw(Long walletId, BigDecimal amount, String idempotencyKey) {
        return applyMoneyOperation(walletId, amount, idempotencyKey, WalletTransaction.Type.WITHDRAW);
    }

    @Transactional(readOnly = true)
    public Optional<WalletTransaction> findTransactionByKey(String idempotencyKey) {
        return walletRepository.findTransactionByIdempotencyKey(idempotencyKey);
    }

    @Transactional(readOnly = true)
    public List<WalletTransaction> listTransactions(Long walletId) {
        getWallet(walletId); // 404 nếu ví không tồn tại
        return walletRepository.listTransactions(walletId);
    }

    /**
     * Cả balance (cache) + bút toán (sổ cái) ghi trong CÙNG transaction —
     * cùng commit hoặc cùng rollback. Idempotency: key đã có -> trả bút toán cũ.
     */
    private WalletTransaction applyMoneyOperation(Long walletId, BigDecimal amount,
                                                  String idempotencyKey, WalletTransaction.Type type) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key must not be blank");
        }
        var existing = walletRepository.findTransactionByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            var tx = existing.get();
            if (!tx.walletId().equals(walletId) || tx.type() != type || tx.amount().compareTo(amount) != 0) {
                throw new IdempotencyKeyConflictException(idempotencyKey);
            }
            return tx; // true retry -> không áp lần hai
        }
        Wallet wallet = getWallet(walletId);
        if (type == WalletTransaction.Type.TOPUP) {
            wallet.topup(amount);
        } else {
            wallet.withdraw(amount); // có thể ném InsufficientFunds -> rollback, không ghi gì
        }
        Wallet saved = walletRepository.save(wallet);
        return walletRepository.saveTransaction(new WalletTransaction(
                null, walletId, type, amount, idempotencyKey, saved.getBalance(), Instant.now()));
    }
}
