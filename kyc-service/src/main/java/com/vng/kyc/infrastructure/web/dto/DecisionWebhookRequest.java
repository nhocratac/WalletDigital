package com.vng.kyc.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import com.vng.kyc.domain.KycDecision;

public record DecisionWebhookRequest(@NotBlank String submissionId,
                                     @NotNull KycDecision.Type decision,
                                     @NotBlank String decidedBy,
                                     String reason) {}
