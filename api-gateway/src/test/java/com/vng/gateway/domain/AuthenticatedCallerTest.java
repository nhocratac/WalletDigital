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
