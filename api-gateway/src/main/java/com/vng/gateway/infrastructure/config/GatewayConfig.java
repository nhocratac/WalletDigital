package com.vng.gateway.infrastructure.config;

import com.vng.gateway.application.GatewayService;
import com.vng.gateway.domain.DownstreamClient;
import com.vng.gateway.domain.GatewayIdentity;
import com.vng.gateway.domain.RequestSigner;
import com.vng.gateway.domain.RouteResolver;
import com.vng.gateway.domain.TokenVerifier;
import com.vng.gateway.infrastructure.routing.RouteTable;
import com.vng.gateway.infrastructure.security.JwtTokenVerifier;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
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
    public RestClient restClient(GatewayProperties props) {
        ClientHttpRequestFactorySettings settings = ClientHttpRequestFactorySettings.defaults()
                .withConnectTimeout(props.getConnectTimeout())
                .withReadTimeout(props.getReadTimeout());
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return RestClient.builder().requestFactory(factory).build();
    }

    @Bean
    public GatewayService gatewayService(RouteResolver routeTable, RequestSigner signer,
                                         DownstreamClient downstreamClient, GatewayIdentity identity) {
        return new GatewayService(routeTable, signer, downstreamClient, identity);
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
