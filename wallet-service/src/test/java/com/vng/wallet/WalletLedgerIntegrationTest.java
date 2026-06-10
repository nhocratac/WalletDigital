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
                .andExpect(jsonPath("$[0].amount").value(100.00))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[1].type").value("WITHDRAW"))
                .andExpect(jsonPath("$[1].amount").value(30.00))
                .andExpect(jsonPath("$[1].balanceAfter").value(70.00));

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

        // Lần rút thất bại KHÔNG được ghi/giữ key -> kiểm tra qua DB, không qua API đang test
        long failedKeyRows = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("x1")).count();
        assertEquals(0, failedKeyRows, "withdraw thất bại không được ghi bút toán cho key x1");

        // Nạp tiền bằng key khác, rồi RETRY với CHÍNH key x1 -> phải thành công (key chưa bị chiếm)
        mockMvc.perform(post("/wallets/" + id + "/topup").header("Idempotency-Key", "x1-fund")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("Idempotency-Key", "x1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(5.00));

        long retriedKeyRows = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("x1")).count();
        assertEquals(1, retriedKeyRows, "retry sau thất bại tạo đúng 1 bút toán cho key x1");
    }

    @Test
    void moneyEndpoints_missingWallet_returns404_andNoLedgerRow() throws Exception {
        mockMvc.perform(post("/wallets/999999/topup").header("Idempotency-Key", "nf-t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());

        mockMvc.perform(post("/wallets/999999/withdraw").header("Idempotency-Key", "nf-w")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());

        long orphanRows = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("nf-t") || t.getIdempotencyKey().equals("nf-w"))
                .count();
        assertEquals(0, orphanRows, "404 tren money endpoint khong duoc ghi but toan / chiem key");

        // Chứng minh key chưa bị chiếm: retry "nf-t" với ví thật -> 200 và đúng 1 bút toán
        long id = createWallet("NotFoundNed");
        mockMvc.perform(post("/wallets/" + id + "/topup").header("Idempotency-Key", "nf-t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(5.00));
        long retriedRows = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("nf-t")).count();
        assertEquals(1, retriedRows, "retry sau 404 tao dung 1 but toan cho key nf-t");
    }

    @Test
    void duplicateWithdrawIdempotencyKey_overHttp_appliesOnce() throws Exception {
        long id = createWallet("Frank");
        mockMvc.perform(post("/wallets/" + id + "/topup").header("Idempotency-Key", "wd-setup")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.00}")).andExpect(status().isOk());

        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("Idempotency-Key", "wd-dup")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":30.00}")).andExpect(status().isOk());
        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("Idempotency-Key", "wd-dup")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":30.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(70.00)); // ket qua CU, khong tru lan 2

        mockMvc.perform(get("/wallets/" + id)).andExpect(jsonPath("$.balance").value(70.00));
        long count = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("wd-dup")).count();
        assertEquals(1, count, "DB chi co dung 1 but toan WITHDRAW cho key nay");
    }

    @Test
    void sameIdempotencyKey_mismatchedPayload_returns422_andSingleLedgerRow() throws Exception {
        long id = createWallet("Grace");
        mockMvc.perform(post("/wallets/" + id + "/topup").header("Idempotency-Key", "mm-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":50.00}"))
                .andExpect(status().isOk());

        // cùng key, amount khác -> 422, không thực thi
        mockMvc.perform(post("/wallets/" + id + "/topup").header("Idempotency-Key", "mm-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":60.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").exists());

        mockMvc.perform(get("/wallets/" + id)).andExpect(jsonPath("$.balance").value(50.00));
        long count = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("mm-key")).count();
        assertEquals(1, count, "ledger chi co dung 1 but toan cho key nay");
    }

    @Test
    void moneyEndpoints_blankIdempotencyKeyHeader_returns400() throws Exception {
        long id = createWallet("Heidi");
        mockMvc.perform(post("/wallets/" + id + "/topup").header("Idempotency-Key", "")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("Idempotency-Key", "")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isBadRequest());
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
