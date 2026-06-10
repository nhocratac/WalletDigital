package com.vng.kyc.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "kyc")
public class KycProperties {
    private String internalHmacSecret;
    private String verifierHmacSecret;   // secret RIÊNG cho webhook — secret segmentation
    private List<String> allowedServices;
    private String revokeRole = "compliance";

    public String getInternalHmacSecret() { return internalHmacSecret; }
    public void setInternalHmacSecret(String v) { this.internalHmacSecret = v; }
    public String getVerifierHmacSecret() { return verifierHmacSecret; }
    public void setVerifierHmacSecret(String v) { this.verifierHmacSecret = v; }
    public List<String> getAllowedServices() { return allowedServices; }
    public void setAllowedServices(List<String> v) { this.allowedServices = v; }
    public String getRevokeRole() { return revokeRole; }
    public void setRevokeRole(String v) { this.revokeRole = v; }
}
