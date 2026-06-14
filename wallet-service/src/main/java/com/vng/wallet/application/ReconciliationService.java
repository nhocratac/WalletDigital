package com.vng.wallet.application;

import com.vng.wallet.domain.BankClient;
import com.vng.wallet.domain.WithdrawalOrder;
import com.vng.wallet.domain.WithdrawalOrderRepository;
import com.vng.wallet.domain.WithdrawalState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Trái tim self-healing (SP4, E6/E8) — quét các order chưa-terminal trong DB (DB chính là
 * hàng-đợi-việc-cần-làm bền vững) và lái mỗi lệnh tới đích. Tách khỏi scheduler để test thuần.
 *
 * <p>Worker KHÔNG tin trí nhớ — nó HỎI ngân hàng (E8). Mọi cú gọi bank dùng LẠI {@code bankRef}
 * của order (E7) → bank dedup, không trả kép sau crash.
 *
 * <ul>
 *   <li><b>PENDING</b> (crash trước ②): hỏi {@code status(bankRef)}; bank "chưa thấy" (UNKNOWN)
 *       -> {@code processSend} (gọi transfer cùng bankRef); đã SETTLED/REJECTED -> áp terminal ngay.
 *   <li><b>SENT</b> (crash sau ②): hỏi {@code status(bankRef)}; SETTLED -> ③ settle; REJECTED -> ③ refund;
 *       UNKNOWN -> giữ SENT + recordUnknownAttempt ("unknown != failed", E9), KHÔNG gọi transfer lại.
 * </ul>
 *
 * <p>Mỗi order bọc try/catch riêng: một order lỗi (kể cả người thua {@code OptimisticLock} của race
 * worker × worker) KHÔNG chặn order khác. Tiền chỉ đổi qua cửa nguyên tử
 * {@link WithdrawalSettlementService} (CAS dưới {@code @Version}).
 */
@Service
public class ReconciliationService {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final WithdrawalOrderRepository orderRepository;
    private final BankClient bankClient;
    private final WithdrawalSettlementService settlementService;
    private final int batchLimit;

    public ReconciliationService(WithdrawalOrderRepository orderRepository,
                                 BankClient bankClient,
                                 WithdrawalSettlementService settlementService,
                                 @Value("${wallet.reconcile.batch-limit:100}") int batchLimit) {
        this.orderRepository = orderRepository;
        this.bankClient = bankClient;
        this.settlementService = settlementService;
        this.batchLimit = batchLimit;
    }

    /** Một vòng đối soát: quét batch order chưa-terminal và lái mỗi lệnh tới đích. */
    public void reconcile() {
        List<WithdrawalOrder> batch = orderRepository.findReconcilable(batchLimit);
        for (WithdrawalOrder order : batch) {
            try {
                reconcileOne(order);
            } catch (Exception e) {
                // Một order lỗi không chặn order khác (race-loser OLE đã nuốt trong service;
                // mọi lỗi khác — vd bank query ném — chỉ log rồi vòng sau thử lại).
                log.warn("reconcile failed for order id={} (will retry next round): {}",
                        order.getId(), e.toString());
            }
        }
    }

    private void reconcileOne(WithdrawalOrder order) {
        BankClient.BankStatus status = bankClient.status(order.getBankRef());
        if (order.getState() == WithdrawalState.PENDING) {
            switch (status) {
                // Bank chưa thấy lệnh -> gửi (cùng bankRef, E7). processSend tự markSent + áp terminal.
                case UNKNOWN -> settlementService.processSend(order);
                case SETTLED -> settlementService.applyTerminal(order.getId(), BankClient.BankStatus.SETTLED);
                case REJECTED -> settlementService.applyTerminal(order.getId(), BankClient.BankStatus.REJECTED);
            }
        } else { // SENT — đã gửi, hỏi lại kết quả dứt khoát (KHÔNG gọi transfer lại)
            switch (status) {
                case SETTLED -> settlementService.applyTerminal(order.getId(), BankClient.BankStatus.SETTLED);
                case REJECTED -> settlementService.applyTerminal(order.getId(), BankClient.BankStatus.REJECTED);
                case UNKNOWN -> settlementService.recordUnknown(order.getId()); // giữ SENT, đếm (E9)
            }
        }
    }
}
