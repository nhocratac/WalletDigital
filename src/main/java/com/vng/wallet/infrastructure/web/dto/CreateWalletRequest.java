package com.vng.wallet.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateWalletRequest(
        @NotBlank(message = "ownerName must not be empty")
        String ownerName
) {
}
