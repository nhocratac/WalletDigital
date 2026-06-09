package com.vng.gateway.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

/** Nạp từ application.yml dưới tiền tố "gateway". */
@ConfigurationProperties(prefix = "gateway")
public class GatewayProperties {

    /** prefix path -> base URL downstream */
    private Map<String, String> routes;
    /** secret HMAC dùng chung với downstream service */
    private String hmacSecret;
    /** serviceId của gateway, gắn vào X-Service-Id */
    private String serviceId = "api-gateway";
    /** public key PEM (Base64 DER, không header) để verify JWT */
    private String jwtPublicKey;

    public Map<String, String> getRoutes() { return routes; }
    public void setRoutes(Map<String, String> routes) { this.routes = routes; }
    public String getHmacSecret() { return hmacSecret; }
    public void setHmacSecret(String hmacSecret) { this.hmacSecret = hmacSecret; }
    public String getServiceId() { return serviceId; }
    public void setServiceId(String serviceId) { this.serviceId = serviceId; }
    public String getJwtPublicKey() { return jwtPublicKey; }
    public void setJwtPublicKey(String jwtPublicKey) { this.jwtPublicKey = jwtPublicKey; }
}
