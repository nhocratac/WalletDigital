package com.vng.wallet.application;

import com.vng.wallet.domain.IdempotencyKeyConflictException;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletNotFoundException;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * USE CASES — điều phối nghiệp vụ. Phụ thuộc PORT (WalletRepository), không biết JPA.
 */
@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final TransactionTemplate txTemplate;

    public WalletService(WalletRepository walletRepository, TransactionTemplate txTemplate) {
        this.walletRepository = walletRepository;
        this.txTemplate = txTemplate;
    }

    @Transactional
    public Wallet createWallet(String userId, String ownerName) {
        return walletRepository.save(Wallet.createNew(userId, ownerName));
    }

    @Transactional(readOnly = true)
    public Wallet getWallet(Long id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> new WalletNotFoundException(id));
    }

    public WalletTransaction topup(Long walletId, BigDecimal amount, String idempotencyKey) {
        return executeWithIdempotentRecovery(walletId, amount, idempotencyKey, WalletTransaction.Type.TOPUP);
    }

    public WalletTransaction withdraw(Long walletId, BigDecimal amount, String idempotencyKey) {
        return executeWithIdempotentRecovery(walletId, amount, idempotencyKey, WalletTransaction.Type.WITHDRAW);
    }

    @Transactional(readOnly = true)
    public List<WalletTransaction> listTransactions(Long walletId) {
        getWallet(walletId); // 404 nếu ví không tồn tại
        return walletRepository.listTransactions(walletId);
    }

    /**
     * Chạy nghiệp vụ tiền trong transaction (TransactionTemplate). Nếu thua race
     * cùng Idempotency-Key (unique constraint -> DataIntegrityViolationException),
     * đọc lại bút toán của người thắng SAU khi transaction thất bại đã kết thúc:
     * khớp payload -> trả lại bút toán cũ (idempotent recovery); không khớp -> 422;
     * người thắng cũng rollback -> ném lại DIVE -> 409 có kiểm soát.
     */
    private WalletTransaction executeWithIdempotentRecovery(Long walletId, BigDecimal amount,
                                                            String idempotencyKey, WalletTransaction.Type type) {
        try {
            return txTemplate.execute(status -> applyMoneyOperation(walletId, amount, idempotencyKey, type));
        } catch (DataIntegrityViolationException e) {
            // Transaction thất bại đã thoát (execute() đã return) -> recovery read an toàn.
            WalletTransaction winner = walletRepository.findTransactionByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e); // winner rolled back -> rethrow -> handler map 409
            requireMatchingTransaction(winner, walletId, type, amount);
            return winner;
        }
    }

    /** Quy tắc khớp payload cho idempotency key — dùng cho cả pre-check lẫn recovery. */
    private static void requireMatchingTransaction(WalletTransaction tx, Long walletId,
                                                   WalletTransaction.Type type, BigDecimal amount) {
        if (!tx.walletId().equals(walletId) || tx.type() != type || tx.amount().compareTo(amount) != 0) {
            throw new IdempotencyKeyConflictException(tx.idempotencyKey());
        }
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
        if (amount == null || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("amount must have at most 2 decimal places");
        }
        var existing = walletRepository.findTransactionByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            var tx = existing.get();
            requireMatchingTransaction(tx, walletId, type, amount);
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
