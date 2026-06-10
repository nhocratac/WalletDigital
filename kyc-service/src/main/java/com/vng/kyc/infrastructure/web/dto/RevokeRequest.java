package com.vng.kyc.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RevokeRequest(@NotBlank String reason) {}
