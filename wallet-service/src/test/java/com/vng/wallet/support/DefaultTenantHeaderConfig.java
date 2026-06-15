package com.vng.wallet.support;

import com.vng.wallet.tenancy.TenantContext;
import com.vng.wallet.tenancy.TenantFilter;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Test support: kể từ SP5 Task 2, {@link TenantFilter} bắt buộc mọi HTTP request mang
 * {@code X-Tenant-Id} (thiếu → 400). Các integration test SP1–SP4 viết trước SP5 không gửi
 * header này. Customizer gắn một tenant mặc định cho MỌI request MockMvc để chúng vẫn chạy
 * như cũ.
 *
 * <p>SP5 Task 4: routing giờ ĐÃ thật. Ngoài việc gắn header cho MockMvc, config này còn cài
 * {@code default} làm fallback process-wide ({@link TenantContext#setDefaultTenant}) để các truy
 * cập DB TRỰC TIẾP trong test (không qua filter — vd {@code txJpa.findAll()} trên thread test) vẫn
 * route về schema mặc định (single-schema baseline) thay vì fail-closed. Production không import
 * config này → thread chưa set tenant vẫn fail-closed.
 */
@TestConfiguration
public class DefaultTenantHeaderConfig {

    public static final String DEFAULT_TENANT = "default";

    @PostConstruct
    void installDefaultTenant() {
        TenantContext.setDefaultTenant(DEFAULT_TENANT);
    }

    @PreDestroy
    void removeDefaultTenant() {
        TenantContext.setDefaultTenant(null);
    }

    @Bean
    public MockMvcBuilderCustomizer defaultTenantHeaderCustomizer() {
        return builder -> builder.defaultRequest(
                get("/").header(TenantFilter.HEADER, DEFAULT_TENANT));
    }
}
