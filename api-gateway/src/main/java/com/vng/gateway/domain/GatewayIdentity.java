package com.vng.gateway.domain;

public record GatewayIdentity(String serviceId, String hmacSecret) {}
