package com.vng.wallet.support;

import com.vng.wallet.tenancy.TenantFilter;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Test support: kể từ SP5 Task 2, {@link TenantFilter} bắt buộc mọi HTTP request mang
 * {@code X-Tenant-Id} (thiếu → 400). Các integration test SP1–SP4 viết trước SP5 không gửi
 * header này. Customizer gắn một tenant mặc định cho MỌI request MockMvc để chúng vẫn chạy
 * như cũ — routing thật sự (chọn schema theo tenant) là việc của Task 4.
 *
 * <p>Đăng ký một {@link MockMvcBuilderCustomizer} — Spring Boot {@code @AutoConfigureMockMvc}
 * áp tất cả bean loại này khi dựng MockMvc.
 */
@TestConfiguration
public class DefaultTenantHeaderConfig {

    public static final String DEFAULT_TENANT = "default";

    @Bean
    public MockMvcBuilderCustomizer defaultTenantHeaderCustomizer() {
        return builder -> builder.defaultRequest(
                get("/").header(TenantFilter.HEADER, DEFAULT_TENANT));
    }
}
