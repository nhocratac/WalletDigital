package com.vng.wallet.application;

import com.vng.wallet.domain.IdempotencyKeyConflictException;
import com.vng.wallet.domain.TransferIdempotencyConflictException;
import com.vng.wallet.domain.KycGate;
import com.vng.wallet.domain.KycNotApprovedException;
import com.vng.wallet.domain.KycUnavailableException;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletNotFoundException;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.domain.WithdrawalOrderRepository;
import com.vng.wallet.tenancy.BankRef;
import com.vng.wallet.tenancy.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * USE CASES — điều phối nghiệp vụ. Phụ thuộc PORT (WalletRepository), không biết JPA.
 * SP3: mọi truy cập ví đều scoped theo userId (D2) — sai chủ -> 404 + audit log (D3).
 */
@Service
public class WalletService {

    private static final Logger log = LoggerFactory.getLogger(WalletService.class);

    private final WalletRepository walletRepository;
    private final WithdrawalOrderRepository withdrawalOrderRepository;
    private final TransactionTemplate txTemplate;
    private final KycGate kycGate;

    public WalletService(WalletRepository walletRepository,
                         WithdrawalOrderRepository withdrawalOrderRepository,
                         TransactionTemplate txTemplate, KycGate kycGate) {
        this.walletRepository = walletRepository;
        this.withdrawalOrderRepository = withdrawalOrderRepository;
        this.txTemplate = txTemplate;
        this.kycGate = kycGate;
    }

    @Transactional
    public Wallet createWallet(String userId, String ownerName) {
        return walletRepository.save(Wallet.createNew(userId, ownerName));
    }

    @Transactional(readOnly = true)
    public Wallet getWallet(Long id, String userId) {
        return walletRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> {
                    log.warn("AUDIT forbidden-or-missing wallet access: walletId={}, callerUserId={}", id, userId);
                    return new WalletNotFoundException(id);   // 404 — giấu tồn tại (D3)
                });
    }

    public WalletTransaction topup(Long walletId, String userId, BigDecimal amount, String idempotencyKey) {
        return executeWithIdempotentRecovery(walletId, userId, amount, idempotencyKey, WalletTransaction.Type.TOPUP);
    }

    /**
     * Thứ tự theo design mục 4: [0] idempotency replay (đọc, NGOÀI tx) →
     * [1+2] cổng KYC (NGOÀI transaction — D4, no remote calls inside a DB transaction) →
     * [3] thực thi trong transaction.
     */
    /**
     * SP4 (E1, E3, bước ①): withdraw không còn tức thời. Tạo {@link WithdrawalOrder} ở
     * PENDING + giữ tiền vào escrow (reserve: available giảm, balance chưa đổi) + ghi
     * ledger WITHDRAW_HOLD — tất cả CÙNG MỘT transaction. Bank CHƯA gọi (worker Task 4–5
     * lái tiếp). Trả order để controller phát 202 Accepted.
     *
     * <p>Cổng KYC (SP3, D4) gọi NGOÀI transaction; replay theo Idempotency-Key trả order cũ.
     */
    public WithdrawalOrder withdraw(Long walletId, String userId, BigDecimal amount, String idempotencyKey) {
        validateKeyAndAmount(idempotencyKey, amount); // các check review Stage 2 — giữ nguyên, gọi trước
        var existing = withdrawalOrderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {                    // [0] replay -> order cũ, KHÔNG đụng gate, KHÔNG hold lần 2
            requireMatchingOrder(existing.get(), walletId, amount);
            return existing.get();
        }
        KycGate.KycCheckResult kyc = kycGate.check(userId);          // [2] NGOÀI transaction (D4)
        switch (kyc.decision()) {
            case DENIED -> throw new KycNotApprovedException(kyc.kycStatus());
            case UNAVAILABLE -> throw new KycUnavailableException();
            case ALLOWED -> { /* qua cổng */ }
        }
        return executeWithdrawWithRecovery(walletId, userId, amount, idempotencyKey);
    }

    /** Poll trạng thái lệnh rút — scoped theo chủ sở hữu (D2): order người khác -> 404. */
    @Transactional(readOnly = true)
    public WithdrawalOrder getWithdrawalOrder(Long walletId, Long orderId, String userId) {
        getWallet(walletId, userId); // 404 nếu ví không thuộc caller (giấu tồn tại)
        return withdrawalOrderRepository.findByIdAndUserId(orderId, userId)
                .filter(o -> o.getWalletId().equals(walletId))
                .orElseThrow(() -> {
                    log.warn("AUDIT forbidden-or-missing withdrawal-order access: orderId={}, walletId={}, callerUserId={}",
                            orderId, walletId, userId);
                    return new WalletNotFoundException(orderId);
                });
    }

    @Transactional(readOnly = true)
    public List<WalletTransaction> listTransactions(Long walletId, String userId) {
        getWallet(walletId, userId); // 404 nếu ví không tồn tại / không thuộc caller
        return walletRepository.listTransactions(walletId);
    }

    /**
     * Chạy nghiệp vụ tiền trong transaction (TransactionTemplate). Nếu thua race
     * cùng Idempotency-Key (unique constraint -> DataIntegrityViolationException),
     * đọc lại bút toán của người thắng SAU khi transaction thất bại đã kết thúc:
     * khớp payload -> trả lại bút toán cũ (idempotent recovery); không khớp -> 422;
     * người thắng cũng rollback -> ném lại DIVE -> 409 có kiểm soát.
     */
    private WalletTransaction executeWithIdempotentRecovery(Long walletId, String userId, BigDecimal amount,
                                                            String idempotencyKey, WalletTransaction.Type type) {
        try {
            return txTemplate.execute(status -> applyMoneyOperation(walletId, userId, amount, idempotencyKey, type));
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

    /** Các check review Stage 2 — key không blank, amount tối đa 2 chữ số thập phân. */
    private static void validateKeyAndAmount(String idempotencyKey, BigDecimal amount) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key must not be blank");
        }
        if (amount == null || amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("amount must have at most 2 decimal places");
        }
    }

    /**
     * Cả balance (cache) + bút toán (sổ cái) ghi trong CÙNG transaction —
     * cùng commit hoặc cùng rollback. Idempotency: key đã có -> trả bút toán cũ.
     * Chỉ còn dùng cho TOPUP (withdraw đã chuyển sang đường order-based, E1).
     */
    private WalletTransaction applyMoneyOperation(Long walletId, String userId, BigDecimal amount,
                                                  String idempotencyKey, WalletTransaction.Type type) {
        validateKeyAndAmount(idempotencyKey, amount);
        var existing = walletRepository.findTransactionByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            var tx = existing.get();
            requireMatchingTransaction(tx, walletId, type, amount);
            return tx; // true retry -> không áp lần hai
        }
        Wallet wallet = getWallet(walletId, userId);
        wallet.topup(amount);
        Wallet saved = walletRepository.save(wallet);
        return walletRepository.saveTransaction(new WalletTransaction(
                null, walletId, type, amount, idempotencyKey, saved.getBalance(), Instant.now()));
    }

    /**
     * ① atomic (E3, E7): trong CÙNG transaction —
     * (1) reserve escrow (available -= amount; balance không đổi; thiếu -> InsufficientFunds),
     * (2) save wallet, (3) sinh bankRef (UNIQUE, cùng tx — mọi retry tới bank dùng LẠI),
     * (4) save WithdrawalOrder PENDING, (5) ghi ledger WITHDRAW_HOLD (balanceAfter = total chưa đổi).
     *
     * <p>Race cùng Idempotency-Key: unique constraint trên order -> DataIntegrityViolationException;
     * sau khi tx thua thoát, đọc lại order người thắng -> khớp payload -> trả; người thắng cũng
     * rollback -> rethrow DIVE -> handler map 409.
     */
    private WithdrawalOrder executeWithdrawWithRecovery(Long walletId, String userId, BigDecimal amount,
                                                        String idempotencyKey) {
        try {
            return txTemplate.execute(status -> applyWithdrawHold(walletId, userId, amount, idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            WithdrawalOrder winner = withdrawalOrderRepository.findByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e); // winner rolled back -> rethrow -> 409
            requireMatchingOrder(winner, walletId, amount);
            return winner;
        }
    }

    private WithdrawalOrder applyWithdrawHold(Long walletId, String userId, BigDecimal amount, String idempotencyKey) {
        validateKeyAndAmount(idempotencyKey, amount);
        var existing = withdrawalOrderRepository.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            var order = existing.get();
            requireMatchingOrder(order, walletId, amount);
            return order; // true retry -> không hold lần hai
        }
        Wallet wallet = getWallet(walletId, userId);
        wallet.reserve(amount); // available -= amount; có thể ném InsufficientFunds -> rollback, không ghi gì
        walletRepository.save(wallet);

        // E7: sinh ở bước ①, cùng tx, dùng LẠI mọi retry tới bank. SP5 T9: nhúng tenant vào bankRef
        // để webhook bank (không có X-Tenant-Id) khôi phục được schema của order trước khi tra cứu.
        String bankRef = BankRef.create(TenantContext.effective());
        WithdrawalOrder saved = withdrawalOrderRepository.save(
                WithdrawalOrder.create(userId, walletId, amount, idempotencyKey, bankRef));
        // Ledger WITHDRAW_HOLD: total (balance) chưa đổi ở bước ① — tiền chỉ chuyển ví->escrow.
        walletRepository.saveTransaction(new WalletTransaction(
                null, walletId, WalletTransaction.Type.WITHDRAW_HOLD, amount, idempotencyKey,
                wallet.getBalance(), Instant.now()));
        return saved;
    }

    /** Quy tắc khớp payload cho Idempotency-Key của order — pre-check lẫn recovery. */
    private static void requireMatchingOrder(WithdrawalOrder order, Long walletId, BigDecimal amount) {
        if (!order.getWalletId().equals(walletId) || order.getAmount().compareTo(amount) != 0) {
            throw new IdempotencyKeyConflictException(order.getIdempotencyKey());
        }
    }

    // ───────────────────────────── SP6 — internal transfer (TR1–TR7) ─────────────────────────────

    /** Kết quả transfer — đủ cho controller dựng TransferResponse. */
    public record TransferResult(String transferId, Long fromWalletId, Long toWalletId, BigDecimal amount) {}

    /**
     * Chuyển tiền ví→ví, TỨC THỜI (không escrow). Thứ tự theo design §3:
     * <pre>
     * [0] validate: amount, key không blank, fromId != toId (self-transfer 400 — TR6)
     * [1] replay (NGOÀI tx): key đã có -> trả transfer cũ, KHÔNG chuyển lần hai (TR7)
     * [2] cổng KYC bên GỬI (NGOÀI tx — D4): DENIED->403, UNAVAILABLE->503 (TR4)
     * [3] transaction: load gửi scoped (D2/404) + nhận by-id (TR5/404) ->
     *     from.withdraw + to.topup + 2 bút toán TRANSFER_OUT(key)/TRANSFER_IN(null) cùng transferId (TR1)
     * </pre>
     * Race cùng key (unique trên chân OUT) -> {@link DataIntegrityViolationException} -> đọc lại
     * transfer người thắng -> khớp payload -> trả; người thắng rollback -> rethrow -> 409 (TR2/TR7).
     */
    public TransferResult transfer(Long fromId, Long toId, String caller, BigDecimal amount, String idempotencyKey) {
        validateKeyAndAmount(idempotencyKey, amount);                  // [0]
        if (fromId.equals(toId)) {                                     // TR6 — self-transfer
            throw new IllegalArgumentException("cannot transfer to the same wallet");
        }
        var existing = walletRepository.findTransactionByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {                                    // [1] replay -> KHÔNG đụng gate, KHÔNG chuyển lần 2
            return replayTransfer(existing.get(), fromId, toId, amount);
        }
        KycGate.KycCheckResult kyc = kycGate.check(caller);            // [2] NGOÀI transaction (D4)
        switch (kyc.decision()) {
            case DENIED -> throw new KycNotApprovedException(kyc.kycStatus());
            case UNAVAILABLE -> throw new KycUnavailableException();
            case ALLOWED -> { /* qua cổng */ }
        }
        return executeTransferWithRecovery(fromId, toId, caller, amount, idempotencyKey);
    }

    private TransferResult executeTransferWithRecovery(Long fromId, Long toId, String caller,
                                                       BigDecimal amount, String idempotencyKey) {
        try {
            return txTemplate.execute(status -> applyTransfer(fromId, toId, caller, amount, idempotencyKey));
        } catch (DataIntegrityViolationException e) {
            WalletTransaction winner = walletRepository.findTransactionByIdempotencyKey(idempotencyKey)
                    .orElseThrow(() -> e); // winner cũng rollback -> rethrow -> handler map 409
            return replayTransfer(winner, fromId, toId, amount);
        }
    }

    /**
     * Replay/recovery của transfer: chân OUT mang (fromId, amount, key) nhưng KHÔNG mang ví nhận,
     * nên ví nhận là PHẦN của payload idempotent (plan Task 3 §8 / design error-contract):
     * soi chân TRANSFER_IN cùng transferId để lấy ví nhận GỐC và so với {@code toId} của lần gọi lại.
     * Lệch from/amount HOẶC lệch ví nhận -> {@link IdempotencyKeyConflictException} (409).
     * Trả về transfer GỐC (ví nhận gốc), không echo {@code toId} của caller.
     */
    private TransferResult replayTransfer(WalletTransaction out, Long fromId, Long toId, BigDecimal amount) {
        // Khác từ/amount -> 409 (transfer-specific để web map 409, KHÔNG dùng base 422 của topup).
        if (!out.walletId().equals(fromId)
                || out.type() != WalletTransaction.Type.TRANSFER_OUT
                || out.amount().compareTo(amount) != 0) {
            throw new TransferIdempotencyConflictException(out.idempotencyKey());
        }
        Long originalToId = walletRepository
                .findTransactionByTransferIdAndType(out.transferId(), WalletTransaction.Type.TRANSFER_IN)
                .map(WalletTransaction::walletId)
                .orElse(null);
        if (originalToId == null || !originalToId.equals(toId)) {       // ví nhận lệch -> khác payload -> 409
            throw new TransferIdempotencyConflictException(out.idempotencyKey());
        }
        return new TransferResult(out.transferId(), out.walletId(), originalToId, out.amount());
    }

    /**
     * Một transaction ACID: debit gửi + credit nhận + 2 bút toán double-entry — cùng commit/rollback.
     * Tổng balance toàn tenant bảo toàn (tiền chỉ đổi chủ). Lock-ordering theo wallet_id tăng dần (TR2).
     */
    private TransferResult applyTransfer(Long fromId, Long toId, String caller,
                                         BigDecimal amount, String idempotencyKey) {
        validateKeyAndAmount(idempotencyKey, amount);
        var existing = walletRepository.findTransactionByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {                                    // true retry trong tx -> KHÔNG chuyển lần hai
            return replayTransfer(existing.get(), fromId, toId, amount);
        }
        // Lock-ordering: nạp/khóa hai ví theo id tăng dần — dễ suy luận, phòng deadlock nếu sau dùng pessimistic.
        Wallet from;
        Wallet to;
        if (fromId <= toId) {
            from = loadSender(fromId, caller);                         // scoped D2 -> 404 nếu sai chủ (TR5)
            to = loadReceiver(toId);                                   // by-id, cùng tenant (SP5 routing) -> 404 (TR5)
        } else {
            to = loadReceiver(toId);
            from = loadSender(fromId, caller);
        }

        from.withdraw(amount);                                         // balance -=; InsufficientFunds -> rollback
        to.topup(amount);                                              // balance +=
        Wallet savedFrom = walletRepository.save(from);                // @Version cả hai
        Wallet savedTo = walletRepository.save(to);

        String transferId = UUID.randomUUID().toString();
        WalletTransaction out = walletRepository.saveTransaction(new WalletTransaction(
                null, fromId, WalletTransaction.Type.TRANSFER_OUT, amount, idempotencyKey,
                savedFrom.getBalance(), Instant.now(), transferId));   // chân OUT giữ key (UNIQUE — TR7)
        walletRepository.saveTransaction(new WalletTransaction(
                null, toId, WalletTransaction.Type.TRANSFER_IN, amount, null,
                savedTo.getBalance(), Instant.now(), transferId));     // chân IN key=null (không replay độc lập)
        return new TransferResult(out.transferId(), fromId, toId, amount);
    }

    private Wallet loadSender(Long fromId, String caller) {
        return walletRepository.findByIdAndUserId(fromId, caller)
                .orElseThrow(() -> {
                    log.warn("AUDIT forbidden-or-missing sender wallet for transfer: walletId={}, callerUserId={}",
                            fromId, caller);
                    return new WalletNotFoundException(fromId); // 404 — giấu tồn tại (D2/D3)
                });
    }

    private Wallet loadReceiver(Long toId) {
        return walletRepository.findById(toId)
                .orElseThrow(() -> new WalletNotFoundException(toId)); // 404 "recipient not found" (TR5/TR3)
    }
}
