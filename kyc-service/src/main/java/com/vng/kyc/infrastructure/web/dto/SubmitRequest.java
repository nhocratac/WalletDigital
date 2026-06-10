package com.vng.kyc.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record SubmitRequest(@NotBlank String userId, @NotEmpty List<String> documentRefs) {}
