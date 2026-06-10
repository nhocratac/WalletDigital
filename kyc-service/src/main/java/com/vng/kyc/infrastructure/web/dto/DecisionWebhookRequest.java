package com.vng.kyc.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.vng.kyc.domain.KycDecision;

public record DecisionWebhookRequest(@NotBlank String submissionId,
                                     @NotNull Decision decision,
                                     @NotBlank String decidedBy,
                                     String reason) {
    /** Enum cục bộ ở boundary webhook — REVOKE không phải decision hợp lệ qua kênh này. */
    public enum Decision {
        APPROVE, REJECT;

        public KycDecision.Type toDomain() {
            return this == APPROVE ? KycDecision.Type.APPROVE : KycDecision.Type.REJECT;
        }
    }
}
