# API Gateway Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Xây `api-gateway` (Spring Boot MVC) verify JWT người dùng (RS256), bóc tenant, ký HMAC theo hợp đồng chung với `wallet-service`, và forward request xuống downstream; map lỗi downstream thành 502/504.

**Architecture:** Clean Architecture áp nhẹ — domain mỏng (value object `AuthenticatedCaller` + 2 port `TokenVerifier`, `DownstreamClient`), application điều phối, infrastructure chứa JWT/HMAC/routing/forwarding. Đồng bộ (servlet) như wallet, KHÔNG reactive.

**Tech Stack:** Java 21, Spring Boot 3.4.4, jjwt 0.12.6 (RS256), Spring `RestClient`, okhttp `mockwebserver` (test), JUnit 5, Maven.

**Bố cục:** Project Maven ĐỘC LẬP tại `api-gateway/` (ngang hàng, không trộn với wallet ở gốc). Gateway chạy port **8081**; wallet **8080**.

**Phạm vi:** verify + route + sign + forward + error-mapping. KHÔNG login, KHÔNG circuit breaker, KHÔNG shared-hmac lib (các stage sau).

---

## Cấu trúc file (sau khi xong)

```
api-gateway/
├── pom.xml
└── src
    ├── main
    │   ├── java/com/vng/gateway/
    │   │   ├── GatewayApplication.java
    │   │   ├── domain/
    │   │   │   ├── AuthenticatedCaller.java        (record userId, tenantId)
    │   │   │   ├── TokenVerifier.java              (PORT)
    │   │   │   ├── DownstreamClient.java           (PORT) + DownstreamRequest/DownstreamResponse
    │   │   │   ├── InvalidTokenException.java
    │   │   │   └── DownstreamException.java         (kèm enum: UPSTREAM_5XX / TIMEOUT)
    │   │   ├── application/
    │   │   │   └── GatewayService.java
    │   │   └── infrastructure/
    │   │       ├── security/
    │   │       │   ├── JwtTokenVerifier.java        (ADAPTER, RS256)
    │   │       │   ├── HmacRequestSigner.java
    │   │       │   └── JwtAuthFilter.java
    │   │       ├── routing/
    │   │       │   ├── RouteTable.java              (+ RouteMatch record)
    │   │       │   ├── RestClientDownstream.java    (ADAPTER)
    │   │       │   └── ForwardingController.java
    │   │       ├── observability/
    │   │       │   └── TraceIdFilter.java
    │   │       └── config/
    │   │           ├── GatewayProperties.java
    │   │           └── GatewayConfig.java           (beans: RouteTable, RestClient, PublicKey)
    │   └── resources/application.yml
    └── test/java/com/vng/gateway/
        ├── security/JwtTokenVerifierTest.java
        ├── security/HmacRequestSignerTest.java
        ├── routing/RouteTableTest.java
        ├── GatewayForwardingIntegrationTest.java
        └── support/RsaTestKeys.java                 (helper sinh khoá + ký token test)
```

---

## Task 1: Scaffold project + smoke test

**Files:**
- Create: `api-gateway/pom.xml`
- Create: `api-gateway/src/main/java/com/vng/gateway/GatewayApplication.java`
- Create: `api-gateway/src/main/resources/application.yml`
- Test:   `api-gateway/src/test/java/com/vng/gateway/GatewayApplicationTest.java`

- [ ] **Step 1: Tạo `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.4</version>
        <relativePath/>
    </parent>
    <groupId>com.vng.gateway</groupId>
    <artifactId>api-gateway</artifactId>
    <version>0.0.1</version>
    <name>api-gateway</name>
    <properties>
        <java.version>21</java.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <!-- jjwt: thư viện JWT (RS256) -->
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-api</artifactId>
            <version>0.12.6</version>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-impl</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>io.jsonwebtoken</groupId>
            <artifactId>jjwt-jackson</artifactId>
            <version>0.12.6</version>
            <scope>runtime</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <!-- MockWebServer: giả lập service downstream trong integration test -->
        <dependency>
            <groupId>com.squareup.okhttp3</groupId>
            <artifactId>mockwebserver</artifactId>
            <version>4.12.0</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: Tạo `GatewayApplication.java`**

```java
package com.vng.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

- [ ] **Step 3: Tạo `application.yml` tối thiểu** (mở rộng ở Task 5)

```yaml
server:
  port: 8081
spring:
  application:
    name: api-gateway
```

- [ ] **Step 4: Viết smoke test**

> Lưu ý: test cấp sẵn một `gateway.jwt-public-key` test (sinh tại chỗ). Ở Task 1 property này chưa dùng (vô hại), nhưng từ Task 7 `GatewayConfig` sẽ cần nó để tạo bean `tokenVerifier` — cấp sẵn ngay từ đầu giúp smoke test KHÔNG vỡ khi context mở rộng.

```java
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
```

- [ ] **Step 5: Chạy test** (sẽ FAIL ở các task sau khi context cần bean chưa có; giờ phải PASS)

Run: `cd api-gateway && mvn -q test -Dtest=GatewayApplicationTest`
Expected: PASS (context khởi động, chưa có controller/filter nào).

- [ ] **Step 6: Commit**

```bash
git add api-gateway/pom.xml api-gateway/src/main/java/com/vng/gateway/GatewayApplication.java api-gateway/src/main/resources/application.yml api-gateway/src/test/java/com/vng/gateway/GatewayApplicationTest.java
git commit -m "feat(gateway): scaffold Spring Boot project on port 8081"
```

---

## Task 2: Domain — `AuthenticatedCaller`, ports, exceptions

**Files:**
- Create: `api-gateway/src/main/java/com/vng/gateway/domain/AuthenticatedCaller.java`
- Create: `api-gateway/src/main/java/com/vng/gateway/domain/TokenVerifier.java`
- Create: `api-gateway/src/main/java/com/vng/gateway/domain/InvalidTokenException.java`
- Create: `api-gateway/src/main/java/com/vng/gateway/domain/DownstreamClient.java`
- Create: `api-gateway/src/main/java/com/vng/gateway/domain/DownstreamException.java`
- Test:   `api-gateway/src/test/java/com/vng/gateway/domain/AuthenticatedCallerTest.java`

- [ ] **Step 1: Viết test thất bại cho value object**

```java
package com.vng.gateway.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AuthenticatedCallerTest {
    @Test
    void holdsUserAndTenant() {
        AuthenticatedCaller caller = new AuthenticatedCaller("user-1", "acme");
        assertEquals("user-1", caller.userId());
        assertEquals("acme", caller.tenantId());
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận FAIL**

Run: `cd api-gateway && mvn -q test -Dtest=AuthenticatedCallerTest`
Expected: FAIL — class chưa tồn tại.

- [ ] **Step 3: Tạo `AuthenticatedCaller` (record thuần Java)**

```java
package com.vng.gateway.domain;

/** Kết quả bóc từ JWT — thuần Java, không Spring/JPA. */
public record AuthenticatedCaller(String userId, String tenantId) {
}
```

- [ ] **Step 4: Tạo port `TokenVerifier` + exception**

```java
package com.vng.gateway.domain;

/** PORT: xác minh một token và trả về danh tính, hoặc ném InvalidTokenException. */
public interface TokenVerifier {
    AuthenticatedCaller verify(String token);
}
```

```java
package com.vng.gateway.domain;

public class InvalidTokenException extends RuntimeException {
    public InvalidTokenException(String message) {
        super(message);
    }
}
```

- [ ] **Step 5: Tạo port `DownstreamClient` + exception (kèm phân loại lỗi)**

```java
package com.vng.gateway.domain;

import java.util.Map;

/**
 * PORT: gửi một request đã ký xuống downstream và trả về response.
 * Dùng record lồng để mô tả request/response một cách thuần khiết.
 */
public interface DownstreamClient {

    record DownstreamRequest(
            String method,
            String baseUrl,
            String path,
            byte[] body,
            Map<String, String> headers
    ) {}

    record DownstreamResponse(
            int status,
            byte[] body,
            Map<String, String> headers
    ) {}

    DownstreamResponse forward(DownstreamRequest request);
}
```

```java
package com.vng.gateway.domain;

/** Lỗi khi gọi downstream. type quyết định gateway trả 502 hay 504. */
public class DownstreamException extends RuntimeException {

    public enum Type { UPSTREAM_5XX, TIMEOUT }

    private final Type type;

    public DownstreamException(Type type, String message) {
        super(message);
        this.type = type;
    }

    public Type getType() {
        return type;
    }
}
```

- [ ] **Step 6: Chạy test để xác nhận PASS**

Run: `cd api-gateway && mvn -q test -Dtest=AuthenticatedCallerTest`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add api-gateway/src/main/java/com/vng/gateway/domain/ api-gateway/src/test/java/com/vng/gateway/domain/
git commit -m "feat(gateway): domain model + TokenVerifier/DownstreamClient ports"
```

---

## Task 3: `JwtTokenVerifier` (RS256) + test helper khoá RSA

**Files:**
- Create: `api-gateway/src/test/java/com/vng/gateway/support/RsaTestKeys.java`
- Create: `api-gateway/src/main/java/com/vng/gateway/infrastructure/security/JwtTokenVerifier.java`
- Test:   `api-gateway/src/test/java/com/vng/gateway/security/JwtTokenVerifierTest.java`

- [ ] **Step 1: Tạo test helper sinh khoá + ký token (dùng trong nhiều test)**

```java
package com.vng.gateway.support;

import io.jsonwebtoken.Jwts;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Date;

/** Sinh cặp khoá RSA và ký JWT test bằng PRIVATE key (giả lập IdP). */
public final class RsaTestKeys {

    public final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;

    public RsaTestKeys() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair pair = gen.generateKeyPair();
            this.publicKey = (RSAPublicKey) pair.getPublic();
            this.privateKey = (RSAPrivateKey) pair.getPrivate();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** Token hợp lệ, hết hạn sau `expiresInSeconds`. */
    public String signToken(String userId, String tenantId, long expiresInSeconds) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(userId)
                .claim("tenantId", tenantId)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expiresInSeconds * 1000))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /** Token đã hết hạn (exp ở quá khứ). */
    public String signExpiredToken(String userId, String tenantId) {
        long past = System.currentTimeMillis() - 60_000;
        return Jwts.builder()
                .subject(userId)
                .claim("tenantId", tenantId)
                .issuedAt(new Date(past - 1000))
                .expiration(new Date(past))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }
}
```

- [ ] **Step 2: Viết test thất bại**

```java
package com.vng.gateway.security;

import com.vng.gateway.domain.AuthenticatedCaller;
import com.vng.gateway.domain.InvalidTokenException;
import com.vng.gateway.infrastructure.security.JwtTokenVerifier;
import com.vng.gateway.support.RsaTestKeys;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenVerifierTest {

    private final RsaTestKeys keys = new RsaTestKeys();
    private final JwtTokenVerifier verifier = new JwtTokenVerifier(keys.publicKey);

    @Test
    void validToken_extractsUserAndTenant() {
        String token = keys.signToken("user-1", "acme", 300);

        AuthenticatedCaller caller = verifier.verify(token);

        assertEquals("user-1", caller.userId());
        assertEquals("acme", caller.tenantId());
    }

    @Test
    void tamperedToken_throws() {
        String token = keys.signToken("user-1", "acme", 300);
        String tampered = token.substring(0, token.length() - 3) + "abc";

        assertThrows(InvalidTokenException.class, () -> verifier.verify(tampered));
    }

    @Test
    void tokenSignedByDifferentKey_throws() {
        RsaTestKeys attacker = new RsaTestKeys();
        String forged = attacker.signToken("user-1", "acme", 300);

        // verifier dùng publicKey của keys, không phải attacker -> phải từ chối
        assertThrows(InvalidTokenException.class, () -> verifier.verify(forged));
    }

    @Test
    void expiredToken_throws() {
        String token = keys.signExpiredToken("user-1", "acme");

        assertThrows(InvalidTokenException.class, () -> verifier.verify(token));
    }
}
```

- [ ] **Step 3: Chạy test để xác nhận FAIL**

Run: `cd api-gateway && mvn -q test -Dtest=JwtTokenVerifierTest`
Expected: FAIL — `JwtTokenVerifier` chưa tồn tại.

- [ ] **Step 4: Tạo `JwtTokenVerifier`**

```java
package com.vng.gateway.infrastructure.security;

import com.vng.gateway.domain.AuthenticatedCaller;
import com.vng.gateway.domain.InvalidTokenException;
import com.vng.gateway.domain.TokenVerifier;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;

import java.security.interfaces.RSAPublicKey;

/**
 * ADAPTER cài TokenVerifier bằng RS256: verify chữ ký bằng PUBLIC key.
 * Gateway KHÔNG có private key nên không thể tự tạo token — chỉ kiểm được.
 */
public class JwtTokenVerifier implements TokenVerifier {

    private final RSAPublicKey publicKey;

    public JwtTokenVerifier(RSAPublicKey publicKey) {
        this.publicKey = publicKey;
    }

    @Override
    public AuthenticatedCaller verify(String token) {
        try {
            Jws<Claims> jws = Jwts.parser()
                    .verifyWith(publicKey)   // kiểm chữ ký; cũng kiểm exp tự động
                    .build()
                    .parseSignedClaims(token);
            Claims claims = jws.getPayload();
            String userId = claims.getSubject();
            String tenantId = claims.get("tenantId", String.class);
            if (userId == null || tenantId == null) {
                throw new InvalidTokenException("Missing sub or tenantId claim");
            }
            return new AuthenticatedCaller(userId, tenantId);
        } catch (JwtException e) {
            // bao gồm chữ ký sai, hết hạn, malformed
            throw new InvalidTokenException("Invalid JWT: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 5: Chạy test để xác nhận PASS**

Run: `cd api-gateway && mvn -q test -Dtest=JwtTokenVerifierTest`
Expected: PASS (4 tests).

- [ ] **Step 6: Commit**

```bash
git add api-gateway/src/main/java/com/vng/gateway/infrastructure/security/JwtTokenVerifier.java api-gateway/src/test/java/com/vng/gateway/security/JwtTokenVerifierTest.java api-gateway/src/test/java/com/vng/gateway/support/RsaTestKeys.java
git commit -m "feat(gateway): RS256 JWT verifier"
```

---

## Task 4: `HmacRequestSigner` + test khoá hợp đồng canonical

**Files:**
- Create: `api-gateway/src/main/java/com/vng/gateway/infrastructure/security/HmacRequestSigner.java`
- Test:   `api-gateway/src/test/java/com/vng/gateway/security/HmacRequestSignerTest.java`

- [ ] **Step 1: Viết test thất bại — KHOÁ định dạng canonical (phải khớp wallet)**

```java
package com.vng.gateway.security;

import com.vng.gateway.infrastructure.security.HmacRequestSigner;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.*;

class HmacRequestSignerTest {

    private final HmacRequestSigner signer = new HmacRequestSigner();

    @Test
    void canonical_hasExactContractFormat() {
        // sha256 của body rỗng (hằng số đã biết)
        String emptySha = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        String expected = String.join("\n",
                "api-gateway",
                "POST",
                "/wallets/1/topup",
                "1749470000",
                emptySha);

        String canonical = signer.buildCanonical("api-gateway", "POST", "/wallets/1/topup",
                "1749470000", new byte[0]);

        assertEquals(expected, canonical, "canonical phải đúng hợp đồng với wallet-service");
    }

    @Test
    void sign_isDeterministicAndMatchesIndependentHmac() throws Exception {
        String secret = "test-secret";
        String canonical = signer.buildCanonical("api-gateway", "GET", "/wallets/1",
                "1749470000", new byte[0]);

        String sig1 = signer.sign(secret, canonical);
        String sig2 = signer.sign(secret, canonical);
        assertEquals(sig1, sig2, "ký phải ổn định (cùng input -> cùng output)");

        // tính độc lập bằng javax.crypto để chắc thuật toán đúng
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] raw = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : raw) hex.append(String.format("%02x", b));
        assertEquals(hex.toString(), sig1);
    }

    @Test
    void canonical_hashesNonEmptyBody() throws Exception {
        byte[] body = "{\"amount\":50}".getBytes(StandardCharsets.UTF_8);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] digest = md.digest(body);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b));

        String canonical = signer.buildCanonical("api-gateway", "POST", "/wallets/1/topup",
                "1749470000", body);

        assertTrue(canonical.endsWith(hex.toString()), "dòng cuối canonical = sha256(body)");
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận FAIL**

Run: `cd api-gateway && mvn -q test -Dtest=HmacRequestSignerTest`
Expected: FAIL — `HmacRequestSigner` chưa tồn tại.

- [ ] **Step 3: Tạo `HmacRequestSigner`**

```java
package com.vng.gateway.infrastructure.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Dựng chuỗi canonical + ký HMAC-SHA256. Định dạng canonical là HỢP ĐỒNG
 * phải khớp y hệt wallet-service.HmacVerifier:
 *   serviceId \n method \n path \n timestamp \n sha256(body)
 */
@Component
public class HmacRequestSigner {

    public String buildCanonical(String serviceId, String method, String path,
                                 String timestamp, byte[] body) {
        return String.join("\n", serviceId, method, path, timestamp, sha256Hex(body));
    }

    public String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return toHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC signing failed", e);
        }
    }

    private String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return toHex(md.digest(body));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 failed", e);
        }
    }

    private String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
```

- [ ] **Step 4: Chạy test để xác nhận PASS**

Run: `cd api-gateway && mvn -q test -Dtest=HmacRequestSignerTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add api-gateway/src/main/java/com/vng/gateway/infrastructure/security/HmacRequestSigner.java api-gateway/src/test/java/com/vng/gateway/security/HmacRequestSignerTest.java
git commit -m "feat(gateway): HMAC request signer (contract-locked canonical)"
```

---

## Task 5: Config (`GatewayProperties`) + `RouteTable`

**Files:**
- Create: `api-gateway/src/main/java/com/vng/gateway/infrastructure/config/GatewayProperties.java`
- Create: `api-gateway/src/main/java/com/vng/gateway/infrastructure/routing/RouteTable.java`
- Modify: `api-gateway/src/main/resources/application.yml`
- Test:   `api-gateway/src/test/java/com/vng/gateway/routing/RouteTableTest.java`

- [ ] **Step 1: Viết test thất bại cho `RouteTable`**

```java
package com.vng.gateway.routing;

import com.vng.gateway.infrastructure.routing.RouteTable;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class RouteTableTest {

    // prefix "/api/wallets" -> base "http://localhost:8080"
    private final RouteTable table = new RouteTable(java.util.Map.of("/api/wallets", "http://localhost:8080"));

    @Test
    void matchingPath_resolvesBaseUrlAndStripsApiPrefix() {
        Optional<RouteTable.RouteMatch> match = table.resolve("/api/wallets/1/topup");

        assertTrue(match.isPresent());
        assertEquals("http://localhost:8080", match.get().baseUrl());
        assertEquals("/wallets/1/topup", match.get().downstreamPath());
    }

    @Test
    void unknownPath_resolvesEmpty() {
        assertTrue(table.resolve("/api/orders/9").isEmpty());
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận FAIL**

Run: `cd api-gateway && mvn -q test -Dtest=RouteTableTest`
Expected: FAIL — `RouteTable` chưa tồn tại.

- [ ] **Step 3: Tạo `RouteTable`**

```java
package com.vng.gateway.infrastructure.routing;

import java.util.Map;
import java.util.Optional;

/**
 * Định tuyến theo prefix path. Downstream path = path gốc bỏ tiền tố "/api".
 * Ví dụ: prefix "/api/wallets" -> base "http://localhost:8080",
 *   "/api/wallets/1/topup" -> base + "/wallets/1/topup".
 */
public class RouteTable {

    public record RouteMatch(String baseUrl, String downstreamPath) {}

    private static final String API_PREFIX = "/api";

    private final Map<String, String> prefixToBaseUrl;

    public RouteTable(Map<String, String> prefixToBaseUrl) {
        this.prefixToBaseUrl = prefixToBaseUrl;
    }

    public Optional<RouteMatch> resolve(String requestPath) {
        for (Map.Entry<String, String> e : prefixToBaseUrl.entrySet()) {
            if (requestPath.startsWith(e.getKey())) {
                String downstreamPath = requestPath.substring(API_PREFIX.length());
                return Optional.of(new RouteMatch(e.getValue(), downstreamPath));
            }
        }
        return Optional.empty();
    }
}
```

- [ ] **Step 4: Tạo `GatewayProperties` (nạp config)**

```java
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
```

- [ ] **Step 5: Cập nhật `application.yml`**

```yaml
server:
  port: 8081
spring:
  application:
    name: api-gateway

gateway:
  service-id: api-gateway
  hmac-secret: ${GATEWAY_HMAC_SECRET:local-dev-secret}
  routes:
    "[/api/wallets]": http://localhost:8080
  # PEM Base64 (chỉ phần DER, bỏ dòng BEGIN/END). Nạp từ env ở production.
  jwt-public-key: ${GATEWAY_JWT_PUBLIC_KEY:}
```

- [ ] **Step 6: Chạy test để xác nhận PASS**

Run: `cd api-gateway && mvn -q test -Dtest=RouteTableTest`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add api-gateway/src/main/java/com/vng/gateway/infrastructure/config/GatewayProperties.java api-gateway/src/main/java/com/vng/gateway/infrastructure/routing/RouteTable.java api-gateway/src/main/resources/application.yml api-gateway/src/test/java/com/vng/gateway/routing/RouteTableTest.java
git commit -m "feat(gateway): RouteTable + config properties"
```

---

## Task 6: `RestClientDownstream` adapter + `GatewayService` + `TraceIdFilter`

**Files:**
- Create: `api-gateway/src/main/java/com/vng/gateway/infrastructure/routing/RestClientDownstream.java`
- Create: `api-gateway/src/main/java/com/vng/gateway/application/GatewayService.java`
- Create: `api-gateway/src/main/java/com/vng/gateway/infrastructure/observability/TraceIdFilter.java`

> Hành vi của các lớp này được kiểm bằng integration test ở Task 8 (cần MockWebServer). Ở task này chỉ tạo code + biên dịch.

- [ ] **Step 1: Tạo `RestClientDownstream` (ADAPTER cài DownstreamClient)**

```java
package com.vng.gateway.infrastructure.routing;

import com.vng.gateway.domain.DownstreamClient;
import com.vng.gateway.domain.DownstreamException;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class RestClientDownstream implements DownstreamClient {

    private final RestClient restClient;

    public RestClientDownstream(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public DownstreamResponse forward(DownstreamRequest req) {
        try {
            return restClient.method(HttpMethod.valueOf(req.method()))
                    .uri(req.baseUrl() + req.path())
                    .headers(h -> req.headers().forEach(h::set))
                    .body(req.body() == null ? new byte[0] : req.body())
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        if (status >= 500) {
                            throw new DownstreamException(DownstreamException.Type.UPSTREAM_5XX,
                                    "Downstream returned " + status);
                        }
                        byte[] body = response.getBody().readAllBytes();
                        Map<String, String> headers = response.getHeaders().toSingleValueMap();
                        return new DownstreamResponse(status, body, headers);
                    });
        } catch (ResourceAccessException e) {
            // timeout / không kết nối được
            throw new DownstreamException(DownstreamException.Type.TIMEOUT,
                    "Downstream unreachable: " + e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Tạo `GatewayService` (điều phối: chọn route → ký → forward)**

```java
package com.vng.gateway.application;

import com.vng.gateway.domain.AuthenticatedCaller;
import com.vng.gateway.domain.DownstreamClient;
import com.vng.gateway.domain.DownstreamClient.DownstreamRequest;
import com.vng.gateway.domain.DownstreamClient.DownstreamResponse;
import com.vng.gateway.infrastructure.config.GatewayProperties;
import com.vng.gateway.infrastructure.routing.RouteTable;
import com.vng.gateway.infrastructure.routing.RouteTable.RouteMatch;
import com.vng.gateway.infrastructure.security.HmacRequestSigner;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Điều phối luồng gateway sau khi JWT đã được verify (filter làm trước):
 * resolve route -> dựng header đã ký -> forward.
 * Ném NoRouteException nếu không khớp route (controller map -> 404).
 */
@Service
public class GatewayService {

    public static class NoRouteException extends RuntimeException {
        public NoRouteException(String path) { super("No route for " + path); }
    }

    private final RouteTable routeTable;
    private final HmacRequestSigner signer;
    private final DownstreamClient downstreamClient;
    private final GatewayProperties props;

    public GatewayService(RouteTable routeTable, HmacRequestSigner signer,
                          DownstreamClient downstreamClient, GatewayProperties props) {
        this.routeTable = routeTable;
        this.signer = signer;
        this.downstreamClient = downstreamClient;
        this.props = props;
    }

    public DownstreamResponse route(String method, String requestPath, byte[] body,
                                    AuthenticatedCaller caller, String traceId, long epochSeconds) {
        Optional<RouteMatch> match = routeTable.resolve(requestPath);
        if (match.isEmpty()) {
            throw new NoRouteException(requestPath);
        }
        RouteMatch route = match.get();

        String timestamp = Long.toString(epochSeconds);
        String canonical = signer.buildCanonical(props.getServiceId(), method,
                route.downstreamPath(), timestamp, body == null ? new byte[0] : body);
        String signature = signer.sign(props.getHmacSecret(), canonical);

        Map<String, String> headers = new HashMap<>();
        headers.put("X-Service-Id", props.getServiceId());
        headers.put("X-Timestamp", timestamp);
        headers.put("X-Signature", signature);
        headers.put("X-Tenant-Id", caller.tenantId());   // bóc từ JWT, KHÔNG từ client
        headers.put("X-Trace-Id", traceId);

        return downstreamClient.forward(
                new DownstreamRequest(method, route.baseUrl(), route.downstreamPath(), body, headers));
    }
}
```

- [ ] **Step 3: Tạo `TraceIdFilter`**

```java
package com.vng.gateway.infrastructure.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/** Đảm bảo mỗi request có X-Trace-Id (truyền tiếp hoặc sinh mới). Chạy sớm. */
@Component
@Order(1)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";
    public static final String ATTR = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        request.setAttribute(ATTR, traceId);
        response.setHeader(HEADER, traceId);
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: Biên dịch để chắc không lỗi**

Run: `cd api-gateway && mvn -q compile`
Expected: BUILD SUCCESS. (Lưu ý: `RestClient` bean được tạo ở Task 7 `GatewayConfig`; compile không cần bean.)

- [ ] **Step 5: Commit**

```bash
git add api-gateway/src/main/java/com/vng/gateway/infrastructure/routing/RestClientDownstream.java api-gateway/src/main/java/com/vng/gateway/application/GatewayService.java api-gateway/src/main/java/com/vng/gateway/infrastructure/observability/TraceIdFilter.java
git commit -m "feat(gateway): downstream adapter + GatewayService + trace filter"
```

---

## Task 7: `JwtAuthFilter` + `ForwardingController` + `GatewayConfig` (beans) + error handler

**Files:**
- Create: `api-gateway/src/main/java/com/vng/gateway/infrastructure/security/JwtAuthFilter.java`
- Create: `api-gateway/src/main/java/com/vng/gateway/infrastructure/routing/ForwardingController.java`
- Create: `api-gateway/src/main/java/com/vng/gateway/infrastructure/config/GatewayConfig.java`
- Create: `api-gateway/src/main/java/com/vng/gateway/infrastructure/web/GatewayExceptionHandler.java`
- Modify: `api-gateway/src/main/java/com/vng/gateway/GatewayApplication.java` (bật `@ConfigurationPropertiesScan`)

- [ ] **Step 1: Tạo `JwtAuthFilter`**

```java
package com.vng.gateway.infrastructure.security;

import com.vng.gateway.domain.AuthenticatedCaller;
import com.vng.gateway.domain.InvalidTokenException;
import com.vng.gateway.domain.TokenVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Verify JWT trước mọi xử lý. 401 nếu thiếu/sai. Chạy sau TraceIdFilter. */
@Component
@Order(2)
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String CALLER_ATTR = "authenticatedCaller";

    private final TokenVerifier tokenVerifier;

    public JwtAuthFilter(TokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            write401(response, "Missing Bearer token");
            return;
        }
        try {
            AuthenticatedCaller caller = tokenVerifier.verify(auth.substring("Bearer ".length()));
            request.setAttribute(CALLER_ATTR, caller);
        } catch (InvalidTokenException e) {
            write401(response, e.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }

    private void write401(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
```

- [ ] **Step 2: Tạo `ForwardingController` (catch-all)**

```java
package com.vng.gateway.infrastructure.routing;

import com.vng.gateway.application.GatewayService;
import com.vng.gateway.domain.AuthenticatedCaller;
import com.vng.gateway.domain.DownstreamClient.DownstreamResponse;
import com.vng.gateway.infrastructure.observability.TraceIdFilter;
import com.vng.gateway.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/** Bắt MỌI path/method, lấy caller (do filter đặt), gọi GatewayService. */
@RestController
public class ForwardingController {

    private final GatewayService gatewayService;

    public ForwardingController(GatewayService gatewayService) {
        this.gatewayService = gatewayService;
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> forward(HttpServletRequest request,
                                          @RequestBody(required = false) byte[] body) {
        AuthenticatedCaller caller = (AuthenticatedCaller) request.getAttribute(JwtAuthFilter.CALLER_ATTR);
        String traceId = (String) request.getAttribute(TraceIdFilter.ATTR);

        DownstreamResponse resp = gatewayService.route(
                request.getMethod(),
                request.getRequestURI(),
                body,
                caller,
                traceId,
                Instant.now().getEpochSecond());

        return ResponseEntity.status(resp.status()).body(resp.body());
    }
}
```

- [ ] **Step 3: Tạo `GatewayConfig` (khai báo beans: RouteTable, RestClient, TokenVerifier)**

```java
package com.vng.gateway.infrastructure.config;

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
```

- [ ] **Step 4: Tạo `GatewayExceptionHandler` (map lỗi → HTTP)**

```java
package com.vng.gateway.infrastructure.web;

import com.vng.gateway.application.GatewayService.NoRouteException;
import com.vng.gateway.domain.DownstreamException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GatewayExceptionHandler {

    @ExceptionHandler(NoRouteException.class)
    public ResponseEntity<Map<String, String>> handleNoRoute(NoRouteException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<Map<String, String>> handleDownstream(DownstreamException ex) {
        HttpStatus status = ex.getType() == DownstreamException.Type.TIMEOUT
                ? HttpStatus.GATEWAY_TIMEOUT      // 504
                : HttpStatus.BAD_GATEWAY;          // 502
        return ResponseEntity.status(status).body(Map.of("error", ex.getMessage()));
    }
}
```

- [ ] **Step 5: Bật `@ConfigurationPropertiesScan` trong `GatewayApplication`**

```java
package com.vng.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
```

- [ ] **Step 6: Biên dịch**

Run: `cd api-gateway && mvn -q compile`
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add api-gateway/src/main/java/com/vng/gateway/
git commit -m "feat(gateway): JWT filter, forwarding controller, beans, error handler"
```

---

## Task 8: Integration test đầu-cuối với MockWebServer

**Files:**
- Test: `api-gateway/src/test/java/com/vng/gateway/GatewayForwardingIntegrationTest.java`

> Test này khởi động full context với một public key test (đẩy qua property động), dựng MockWebServer đóng vai wallet, gửi request thật vào gateway qua TestRestTemplate.

- [ ] **Step 1: Viết integration test thất bại**

```java
package com.vng.gateway;

import com.vng.gateway.support.RsaTestKeys;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Base64;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayForwardingIntegrationTest {

    static RsaTestKeys keys = new RsaTestKeys();
    static MockWebServer wallet;

    @LocalServerPort
    int gatewayPort;

    @Autowired
    TestRestTemplate rest;

    @BeforeAll
    static void startMock() throws Exception {
        wallet = new MockWebServer();
        wallet.start();
    }

    @AfterAll
    static void stopMock() throws Exception {
        wallet.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry reg) {
        reg.add("gateway.jwt-public-key",
                () -> Base64.getEncoder().encodeToString(keys.publicKey.getEncoded()));
        reg.add("gateway.hmac-secret", () -> "it-secret");
        reg.add("gateway.routes.[/api/wallets]", () -> "http://localhost:" + wallet.getPort());
    }

    private HttpHeaders authHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    @Test
    void validJwt_forwardsSignedRequestWithTenantFromToken() throws Exception {
        wallet.enqueue(new MockResponse().setResponseCode(200).setBody("{\"id\":1}"));
        String token = keys.signToken("user-1", "acme", 300);

        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + gatewayPort + "/api/wallets/1",
                HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);

        assertEquals(200, resp.getStatusCode().value());

        RecordedRequest forwarded = wallet.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(forwarded);
        assertEquals("/wallets/1", forwarded.getPath());
        assertEquals("acme", forwarded.getHeader("X-Tenant-Id"));   // bóc từ JWT
        assertEquals("api-gateway", forwarded.getHeader("X-Service-Id"));
        assertNotNull(forwarded.getHeader("X-Signature"));
        assertNotNull(forwarded.getHeader("X-Timestamp"));
    }

    @Test
    void missingJwt_returns401AndDoesNotForward() {
        ResponseEntity<String> resp = rest.getForEntity(
                "http://localhost:" + gatewayPort + "/api/wallets/1", String.class);

        assertEquals(401, resp.getStatusCode().value());
        assertEquals(0, wallet.getRequestCount());
    }

    @Test
    void downstream5xx_mapsTo502() {
        wallet.enqueue(new MockResponse().setResponseCode(500));
        String token = keys.signToken("user-1", "acme", 300);

        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + gatewayPort + "/api/wallets/1",
                HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);

        assertEquals(502, resp.getStatusCode().value());
    }

    @Test
    void unknownRoute_returns404() {
        String token = keys.signToken("user-1", "acme", 300);

        ResponseEntity<String> resp = rest.exchange(
                "http://localhost:" + gatewayPort + "/api/orders/9",
                HttpMethod.GET, new HttpEntity<>(authHeaders(token)), String.class);

        assertEquals(404, resp.getStatusCode().value());
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận FAIL trước (nếu code thiếu) rồi PASS**

Run: `cd api-gateway && mvn -q test -Dtest=GatewayForwardingIntegrationTest`
Expected: PASS (4 tests). Nếu FAIL vì timeout-mapping (504) chưa kích hoạt được bằng MockWebServer, đó là test riêng — 504 đã có nhánh code; ở đây ta kiểm 401/200/502/404 là đủ cho integration.

- [ ] **Step 3: Chạy TOÀN BỘ test**

Run: `cd api-gateway && mvn -q test`
Expected: PASS tất cả (AuthenticatedCallerTest, JwtTokenVerifierTest 4, HmacRequestSignerTest 3, RouteTableTest 2, GatewayForwardingIntegrationTest 4, GatewayApplicationTest).

- [ ] **Step 4: Commit**

```bash
git add api-gateway/src/test/java/com/vng/gateway/GatewayForwardingIntegrationTest.java
git commit -m "test(gateway): end-to-end forwarding with MockWebServer (200/401/502/404)"
```

---

## Định nghĩa "Done"

- `cd api-gateway && mvn -q test` xanh toàn bộ.
- `JwtTokenVerifier` từ chối token sai chữ ký / sai khoá / hết hạn.
- `HmacRequestSigner.buildCanonical` khớp đúng định dạng hợp đồng với `wallet-service`.
- Integration test chứng minh: JWT hợp lệ → forward kèm `X-Tenant-Id` (bóc từ token) + `X-Signature`; thiếu JWT → 401 không forward; downstream 5xx → 502; route lạ → 404.
- `domain/` không import Spring (kiểm `AuthenticatedCaller`, `TokenVerifier`, `DownstreamClient`).

## Bước kế tiếp (plan riêng)
- Circuit Breaker (Resilience4j) bọc `RestClientDownstream` (xử lý chuỗi timeout).
- Tách thư viện `shared-hmac` dùng chung canonical giữa gateway & wallet.
- Chạy thật end-to-end: bật cả wallet (8080) + gateway (8081), ký token bằng private key, gọi xuyên 2 service.
