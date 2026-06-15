package com.vng.wallet.tenancy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantContextTest {

    @AfterEach
    void cleanup() {
        TenantContext.clear();
    }

    @Test
    void set_then_get_returns_value() {
        TenantContext.set("acme");
        assertThat(TenantContext.get()).isEqualTo("acme");
    }

    @Test
    void clear_resets_to_null() {
        TenantContext.set("acme");
        TenantContext.clear();
        assertThat(TenantContext.get()).isNull();
    }

    @Test
    void get_without_set_is_null() {
        assertThat(TenantContext.get()).isNull();
    }
}
