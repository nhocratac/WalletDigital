package com.vng.wallet.infrastructure.kyc;

import com.vng.wallet.domain.KycGate;
import org.springframework.stereotype.Component;

/** TẠM cho Task 4 — Task 5 thay bằng RestKycGate (xoá file này). */
@Component
public class AllowAllKycGate implements KycGate {
    @Override
    public KycCheckResult check(String userId) {
        return new KycCheckResult(Decision.ALLOWED, "APPROVED");
    }
}
