package com.vng.wallet.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record MoneyRequest(@NotNull @Positive(message = "amount must be positive") BigDecimal amount) {}
