package com.vng.wallet.infrastructure.web;

import com.vng.wallet.application.WalletService;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.Optional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WalletController.class)
@Import({GlobalExceptionHandler.class, WalletControllerTest.TestStubConfig.class})
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * Cung cấp WalletService thật, được "tiêm" một WalletRepository stub.
     * WalletRepository là PORT (interface) nên stub được bằng anonymous class —
     * không cần Mockito mock class cụ thể (tránh lỗi byte-buddy trên JDK mới).
     */
    @TestConfiguration
    static class TestStubConfig {
        @Bean
        WalletService walletService() {
            return new WalletService(new WalletRepository() {
                @Override
                public Wallet save(Wallet wallet) {
                    // Stub: gán id cố định, giữ nguyên ownerName + balance (0 cho ví mới).
                    return new Wallet(1L, wallet.getOwnerName(), wallet.getBalance(), 0L);
                }

                @Override
                public Optional<Wallet> findById(Long id) {
                    // Stub: chỉ ví id=1 tồn tại (số dư 250.00); id khác -> rỗng -> 404.
                    if (id == 1L) {
                        return Optional.of(new Wallet(1L, "Existing Owner", new BigDecimal("250.00"), 0L));
                    }
                    return Optional.empty();
                }
            });
        }
    }

    @Test
    void createWallet_returns201WithZeroBalance() throws Exception {
        mockMvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Alice\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.ownerName").value("Alice"))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void getExistingWallet_returns200WithBody() throws Exception {
        mockMvc.perform(get("/wallets/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.ownerName").value("Existing Owner"))
                .andExpect(jsonPath("$.balance").value(250.00));
    }

    @Test
    void getMissingWallet_returns404() throws Exception {
        mockMvc.perform(get("/wallets/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Wallet not found with id: 999999"));
    }

    @Test
    void createWallet_emptyOwner_returns400() throws Exception {
        mockMvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ownerName must not be empty"));
    }

    @Test
    void createWallet_nullOwner_returns400() throws Exception {
        mockMvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ownerName must not be empty"));
    }

    @Test
    void createWallet_whitespaceOwner_returns400() throws Exception {
        mockMvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ownerName must not be empty"));
    }
}
