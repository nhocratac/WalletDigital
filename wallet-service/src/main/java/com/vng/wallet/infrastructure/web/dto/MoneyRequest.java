package com.vng.wallet.infrastructure.web.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record MoneyRequest(
        @NotNull
        @Positive(message = "amount must be positive")
        @Digits(integer = 36, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal amount) {}
