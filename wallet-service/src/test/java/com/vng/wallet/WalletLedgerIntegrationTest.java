package com.vng.wallet;

import com.vng.wallet.infrastructure.persistence.SpringDataWalletTransactionJpa;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class WalletLedgerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SpringDataWalletTransactionJpa txJpa;

    private long createWallet(String owner) throws Exception {
        MvcResult r = mockMvc.perform(post("/wallets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"" + owner + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return Long.parseLong(r.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void fullFlow_topupWithdrawHistory() throws Exception {
        long id = createWallet("Alice");

        mockMvc.perform(post("/wallets/" + id + "/topup")
                        .header("Idempotency-Key", "t1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(100.00));

        mockMvc.perform(post("/wallets/" + id + "/withdraw")
                        .header("Idempotency-Key", "w1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":30.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(70.00));

        mockMvc.perform(get("/wallets/" + id + "/transactions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("TOPUP"))
                .andExpect(jsonPath("$[1].type").value("WITHDRAW"));

        mockMvc.perform(get("/wallets/" + id))
                .andExpect(jsonPath("$.balance").value(70.00));
    }

    @Test
    void duplicateIdempotencyKey_overHttp_appliesOnce() throws Exception {
        long id = createWallet("Bob");
        String body = "{\"amount\":50.00}";

        mockMvc.perform(post("/wallets/" + id + "/topup").header("Idempotency-Key", "dup-http")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mockMvc.perform(post("/wallets/" + id + "/topup").header("Idempotency-Key", "dup-http")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(50.00)); // kết quả CŨ, không cộng lần 2

        mockMvc.perform(get("/wallets/" + id)).andExpect(jsonPath("$.balance").value(50.00));
        long count = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("dup-http")).count();
        assertEquals(1, count, "DB chỉ có đúng 1 bút toán cho key này");
    }

    @Test
    void withdraw_insufficient_returns422_andNoLedgerRow() throws Exception {
        long id = createWallet("Carol");

        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("Idempotency-Key", "x1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").exists());

        mockMvc.perform(get("/wallets/" + id + "/transactions"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void topup_missingIdempotencyKeyHeader_returns400() throws Exception {
        long id = createWallet("Dave");
        mockMvc.perform(post("/wallets/" + id + "/topup")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void topup_negativeAmount_returns400() throws Exception {
        long id = createWallet("Eve");
        mockMvc.perform(post("/wallets/" + id + "/topup").header("Idempotency-Key", "n1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":-5.00}"))
                .andExpect(status().isBadRequest());
    }
}
