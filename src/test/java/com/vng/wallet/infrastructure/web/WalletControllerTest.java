package com.vng.wallet.infrastructure.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WalletControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createWallet_returns201WithZeroBalance() throws Exception {
        mockMvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Alice\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.ownerName").value("Alice"))
                .andExpect(jsonPath("$.balance").value(0));
    }

    @Test
    void getMissingWallet_returns404() throws Exception {
        mockMvc.perform(get("/wallets/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void createWallet_emptyOwner_returns400() throws Exception {
        mockMvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
