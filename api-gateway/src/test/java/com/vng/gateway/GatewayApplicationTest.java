package com.vng.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.KeyPairGenerator;
import java.util.Base64;

@SpringBootTest
class GatewayApplicationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("gateway.jwt-public-key", () -> {
            try {
                KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
                gen.initialize(2048);
                return Base64.getEncoder().encodeToString(gen.generateKeyPair().getPublic().getEncoded());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        reg.add("gateway.hmac-secret", () -> "smoke-secret");
        reg.add("gateway.routes.[/api/wallets]", () -> "http://localhost:8080");
    }

    @Test
    void contextLoads() {
    }
}
