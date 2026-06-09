package com.vng.gateway.infrastructure.config;

import com.vng.gateway.domain.GatewayIdentity;
import com.vng.gateway.domain.TokenVerifier;
import com.vng.gateway.infrastructure.routing.RouteTable;
import com.vng.gateway.infrastructure.security.JwtTokenVerifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.security.KeyFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

@Configuration
public class GatewayConfig {

    @Bean
    public RouteTable routeTable(GatewayProperties props) {
        return new RouteTable(props.getRoutes());
    }

    @Bean
    public GatewayIdentity gatewayIdentity(GatewayProperties props) {
        return new GatewayIdentity(props.getServiceId(), props.getHmacSecret());
    }

    @Bean
    public RestClient restClient() {
        return RestClient.create();
    }

    @Bean
    public TokenVerifier tokenVerifier(GatewayProperties props) {
        return new JwtTokenVerifier(parsePublicKey(props.getJwtPublicKey()));
    }

    private RSAPublicKey parsePublicKey(String base64Der) {
        try {
            byte[] der = Base64.getDecoder().decode(base64Der);
            return (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new IllegalStateException("Invalid gateway.jwt-public-key", e);
        }
    }
}
