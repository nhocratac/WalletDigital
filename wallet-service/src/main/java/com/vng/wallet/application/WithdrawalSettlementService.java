package com.vng.wallet.application;

import com.vng.wallet.domain.BankClient;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import com.vng.wallet.domain.WalletTransaction;
import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.domain.WithdrawalOrderRepository;
import com.vng.wallet.domain.WithdrawalState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;

/**
 * ②③ settle/refund — CỬA NGUYÊN TỬ DUY NHẤT của vòng đời rút (SP4, E5/E8).
 *
 * <p>Mọi đường terminal-hoá order (reconciliation worker — slow path; bank webhook — fast path;
 * admin resolve) BẮT BUỘC đi qua {@link #applyTerminal(Long, BankClient.BankStatus)}. Lật state +
 * đổi tiền (settle/release) là MỘT thao tác nguyên tử (CAS) trong CÙNG transaction, gated bởi
 * {@code @Version} của order → exactly-once: hai actor đua thì người thua đụng
 * {@link OptimisticLockingFailureException} → rollback toàn bộ tx → KHÔNG có lần đổi tiền thứ hai.
 *
 * <p>Cú gọi bank ({@code transfer}/{@code status}) nằm NGOÀI transaction (D4/E5); chỉ phần
 * lật-state-đổi-tiền vào trong {@code applyTerminal}.
 */
@Service
public class WithdrawalSettlementService {

    private static final Logger log = LoggerFactory.getLogger(WithdrawalSettlementService.class);

    private final WithdrawalOrderRepository orderRepository;
    private final WalletRepository walletRepository;
    private final BankClient bankClient;
    private final TransactionTemplate txTemplate;

    public WithdrawalSettlementService(WithdrawalOrderRepository orderRepository,
                                       WalletRepository walletRepository,
                                       BankClient bankClient, TransactionTemplate txTemplate) {
        this.orderRepository = orderRepository;
        this.walletRepository = walletRepository;
        this.bankClient = bankClient;
        this.txTemplate = txTemplate;
    }

    /**
     * ② Gửi lệnh tới bank (NGOÀI tx) rồi áp kết quả qua cửa nguyên tử:
     * SETTLED -> applyTerminal(SETTLED); REJECTED -> applyTerminal(FAILED);
     * UNKNOWN/timeout -> KHÔNG terminal (E9), chỉ đánh dấu SENT + recordUnknownAttempt.
     * Dùng LẠI {@code bankRef} của order (E7) — mọi retry tới bank idempotent.
     */
    public void processSend(WithdrawalOrder order) {
        WithdrawalOrder sent = markSent(order.getId()); // SENT + firstSentAt trong tx (CAS)
        if (sent == null) {
            return; // đã terminal bởi đường khác -> không gửi nữa
        }
        BankClient.BankStatus result = bankClient.transfer(sent.getBankRef(), sent.getAmount()).result();
        switch (result) {
            case SETTLED -> applyTerminal(sent.getId(), BankClient.BankStatus.SETTLED);
            case REJECTED -> applyTerminal(sent.getId(), BankClient.BankStatus.REJECTED);
            case UNKNOWN -> recordUnknown(sent.getId()); // "unknown != failed" — giữ SENT
        }
    }

    /** Lật PENDING|SENT -> SENT (đặt firstSentAt lần đầu). null nếu order đã terminal. */
    private WithdrawalOrder markSent(Long orderId) {
        return txTemplate.execute(status -> {
            WithdrawalOrder order = orderRepository.findById(orderId).orElseThrow();
            if (order.getState().isTerminal()) {
                return null;
            }
            order.markSent();
            return orderRepository.save(order);
        });
    }

    /** UNKNOWN ("unknown != failed", E9): giữ SENT, chỉ đếm attempt. Dùng bởi reconciliation worker. */
    public void recordUnknown(Long orderId) {
        txTemplate.executeWithoutResult(status -> {
            WithdrawalOrder order = orderRepository.findById(orderId).orElseThrow();
            if (order.getState().isTerminal()) {
                return;
            }
            order.recordUnknownAttempt();
            order.escalateIfExhausted(); // E10: quá ngưỡng N/T -> NEEDS_MANUAL_REVIEW (KHÔNG đổi tiền)
            orderRepository.save(order);
        });
    }

    /**
     * CỬA NGUYÊN TỬ: reload order theo id (kèm @Version) rồi áp terminal exactly-once.
     * Đã terminal -> no-op (idempotent tuần tự). Người thua race -> OptimisticLock -> nuốt + log.
     */
    public void applyTerminal(Long orderId, BankClient.BankStatus outcome) {
        WithdrawalOrder order = orderRepository.findById(orderId).orElseThrow();
        applyTerminalOn(order, outcome);
    }

    /**
     * E10 admin resolve: con người quyết một order in-doubt (thường NEEDS_MANUAL_REVIEW) — đi qua
     * ĐÚNG cửa nguyên tử {@link #applyTerminal} (worker × webhook × admin chung một cửa, exactly-once).
     * {@code decision} SETTLED -> settle; FAILED -> refund. Trả order sau khi áp để controller phản hồi.
     */
    public WithdrawalOrder resolveManual(Long orderId, WithdrawalState decision) {
        BankClient.BankStatus outcome = switch (decision) {
            case SETTLED -> BankClient.BankStatus.SETTLED;
            case FAILED -> BankClient.BankStatus.REJECTED;
            default -> throw new IllegalArgumentException(
                    "admin decision chỉ nhận SETTLED|FAILED, got " + decision);
        };
        applyTerminal(orderId, outcome);
        return orderRepository.findById(orderId).orElseThrow();
    }

    /**
     * Biến thể nhận instance order do caller giữ (có thể version cũ) — mô phỏng đua thực:
     * hai actor cùng load order ở version V, mỗi người gọi với instance của mình; người đầu
     * save (V->V+1), người thứ hai save đụng OptimisticLock -> rollback -> không đổi tiền lần 2.
     */
    public void applyTerminalOn(WithdrawalOrder order, BankClient.BankStatus outcome) {
        try {
            txTemplate.executeWithoutResult(status -> {
                if (order.getState().isTerminal()) {
                    return; // idempotent tuần tự — actor sau thấy đã terminal
                }
                Wallet wallet = walletRepository.findById(order.getWalletId()).orElseThrow();
                WalletTransaction.Type ledgerType;
                if (outcome == BankClient.BankStatus.SETTLED) {
                    order.markSettled();
                    wallet.settle(order.getAmount());           // balance -= amount; held -= amount
                    ledgerType = WalletTransaction.Type.WITHDRAW_SETTLED;
                } else if (outcome == BankClient.BankStatus.REJECTED) {
                    order.markFailed("bank rejected");
                    wallet.release(order.getAmount());          // held -= amount (available phục hồi)
                    ledgerType = WalletTransaction.Type.WITHDRAW_REFUNDED;
                } else {
                    throw new IllegalArgumentException("applyTerminal chỉ nhận SETTLED|REJECTED, got " + outcome);
                }
                // Save order TRƯỚC (CAS dưới @Version): người thua đụng OptimisticLock ở đây,
                // ví CHƯA bị đụng -> rollback an toàn (không đổi tiền lần hai).
                orderRepository.save(order);
                Wallet saved = walletRepository.save(wallet);
                walletRepository.saveTransaction(new WalletTransaction(
                        null, wallet.getId(), ledgerType, order.getAmount(),
                        order.getBankRef(), saved.getBalance(), Instant.now()));
            });
        } catch (OptimisticLockingFailureException e) {
            // Kết quả MONG ĐỢI của race (worker × webhook × admin): người thua bỏ qua an toàn.
            log.info("applyTerminal lost optimistic race for order id={} (already terminalized by another actor)",
                    order.getId());
        }
    }
}
