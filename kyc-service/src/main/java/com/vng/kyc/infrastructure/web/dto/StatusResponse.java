package com.vng.kyc.infrastructure.web.dto;

import com.vng.kyc.domain.KycStatus;

public record StatusResponse(String userId, KycStatus status) {}
