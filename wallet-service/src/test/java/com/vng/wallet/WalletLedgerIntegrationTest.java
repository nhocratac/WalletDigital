package com.vng.wallet;

import com.vng.wallet.infrastructure.persistence.SpringDataWalletTransactionJpa;
import com.vng.wallet.support.AllowAllKycGateTestConfig;
import com.vng.wallet.support.DefaultTenantHeaderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Import({AllowAllKycGateTestConfig.class, DefaultTenantHeaderConfig.class})   // mục đích file này là LEDGER, không phải gate (Task 5 warning)
class WalletLedgerIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired SpringDataWalletTransactionJpa txJpa;

    private long createWallet(String owner) throws Exception {
        MvcResult r = mockMvc.perform(post("/wallets")
                        .header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"" + owner + "\"}"))
                .andExpect(status().isCreated()).andReturn();
        return Long.parseLong(r.getResponse().getContentAsString()
                .replaceAll(".*\"id\":(\\d+).*", "$1"));
    }

    @Test
    void withdraw_fromAnotherUsersWallet_returns404() throws Exception {
        long id = createWallet("Alice"); // helper gửi X-User-Id: user-1
        mockMvc.perform(post("/wallets/" + id + "/withdraw")
                        .header("X-User-Id", "user-EVIL").header("Idempotency-Key", "evil-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":1.00}"))
                .andExpect(status().isNotFound());   // KHÔNG phải 403 — D3
    }

    @Test
    void createWallet_missingUserIdHeader_returns400() throws Exception {
        mockMvc.perform(post("/wallets").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"X\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fullFlow_topupWithdrawHistory() throws Exception {
        long id = createWallet("Alice");

        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1")
                        .header("Idempotency-Key", "t1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(100.00));

        // E1: withdraw -> 202 + order PENDING; buoc ① chi HOLD (total chua doi).
        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("X-User-Id", "user-1")
                        .header("Idempotency-Key", "w1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":30.00}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(30.00));

        // Ledger: TOPUP roi WITHDRAW_HOLD (balanceAfter = total = 100, chua roi he).
        mockMvc.perform(get("/wallets/" + id + "/transactions").header("X-User-Id", "user-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].type").value("TOPUP"))
                .andExpect(jsonPath("$[0].amount").value(100.00))
                .andExpect(jsonPath("$[0].createdAt").exists())
                .andExpect(jsonPath("$[1].type").value("WITHDRAW_HOLD"))
                .andExpect(jsonPath("$[1].amount").value(30.00))
                .andExpect(jsonPath("$[1].balanceAfter").value(100.00));

        // total van 100 (escrow); available = 70 nhung balance field = total.
        mockMvc.perform(get("/wallets/" + id).header("X-User-Id", "user-1"))
                .andExpect(jsonPath("$.balance").value(100.00));
    }

    @Test
    void duplicateIdempotencyKey_overHttp_appliesOnce() throws Exception {
        long id = createWallet("Bob");
        String body = "{\"amount\":50.00}";

        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1").header("Idempotency-Key", "dup-http")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1").header("Idempotency-Key", "dup-http")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(50.00)); // kết quả CŨ, không cộng lần 2

        mockMvc.perform(get("/wallets/" + id).header("X-User-Id", "user-1")).andExpect(jsonPath("$.balance").value(50.00));
        long count = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("dup-http")).count();
        assertEquals(1, count, "DB chỉ có đúng 1 bút toán cho key này");
    }

    @Test
    void withdraw_insufficient_returns422_andNoLedgerRow() throws Exception {
        long id = createWallet("Carol");

        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("X-User-Id", "user-1").header("Idempotency-Key", "x1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").exists());

        mockMvc.perform(get("/wallets/" + id + "/transactions").header("X-User-Id", "user-1"))
                .andExpect(jsonPath("$.length()").value(0));

        // Lần rút thất bại KHÔNG được ghi/giữ key -> kiểm tra qua DB, không qua API đang test
        long failedKeyRows = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("x1")).count();
        assertEquals(0, failedKeyRows, "withdraw thất bại không được ghi bút toán cho key x1");

        // Nạp tiền bằng key khác, rồi RETRY với CHÍNH key x1 -> phải thành công (key chưa bị chiếm)
        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1").header("Idempotency-Key", "x1-fund")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":10.00}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("X-User-Id", "user-1").header("Idempotency-Key", "x1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("PENDING"));

        long retriedKeyRows = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("x1")).count();
        assertEquals(1, retriedKeyRows, "retry sau thất bại tạo đúng 1 bút toán WITHDRAW_HOLD cho key x1");
    }

    @Test
    void moneyEndpoints_missingWallet_returns404_andNoLedgerRow() throws Exception {
        mockMvc.perform(post("/wallets/999999/topup").header("X-User-Id", "user-1").header("Idempotency-Key", "nf-t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());

        mockMvc.perform(post("/wallets/999999/withdraw").header("X-User-Id", "user-1").header("Idempotency-Key", "nf-w")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());

        long orphanRows = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("nf-t") || t.getIdempotencyKey().equals("nf-w"))
                .count();
        assertEquals(0, orphanRows, "404 tren money endpoint khong duoc ghi but toan / chiem key");

        // Chứng minh key chưa bị chiếm: retry "nf-t" với ví thật -> 200 và đúng 1 bút toán
        long id = createWallet("NotFoundNed");
        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1").header("Idempotency-Key", "nf-t")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceAfter").value(5.00));
        long retriedRows = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("nf-t")).count();
        assertEquals(1, retriedRows, "retry sau 404 tao dung 1 but toan cho key nf-t");
    }

    @Test
    void listTransactions_missingWallet_returns404() throws Exception {
        mockMvc.perform(get("/wallets/999999/transactions").header("X-User-Id", "user-1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void duplicateWithdrawIdempotencyKey_overHttp_appliesOnce() throws Exception {
        long id = createWallet("Frank");
        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1").header("Idempotency-Key", "wd-setup")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":100.00}")).andExpect(status().isOk());

        String orderId = mockMvc.perform(post("/wallets/" + id + "/withdraw").header("X-User-Id", "user-1").header("Idempotency-Key", "wd-dup")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":30.00}"))
                .andExpect(status().isAccepted()).andReturn()
                .getResponse().getContentAsString().replaceAll(".*\"orderId\":(\\d+).*", "$1");
        // replay cung key -> 202 + CUNG orderId (khong hold lan 2)
        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("X-User-Id", "user-1").header("Idempotency-Key", "wd-dup")
                .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":30.00}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.orderId").value(Long.parseLong(orderId)));

        // total van 100 (escrow); held 30 chi mot lan.
        mockMvc.perform(get("/wallets/" + id).header("X-User-Id", "user-1")).andExpect(jsonPath("$.balance").value(100.00));
        long count = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("wd-dup")).count();
        assertEquals(1, count, "DB chi co dung 1 but toan WITHDRAW_HOLD cho key nay");
    }

    @Test
    void sameIdempotencyKey_mismatchedPayload_returns422_andSingleLedgerRow() throws Exception {
        long id = createWallet("Grace");
        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1").header("Idempotency-Key", "mm-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":50.00}"))
                .andExpect(status().isOk());

        // cùng key, amount khác -> 422, không thực thi
        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1").header("Idempotency-Key", "mm-key")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":60.00}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").exists());

        mockMvc.perform(get("/wallets/" + id).header("X-User-Id", "user-1")).andExpect(jsonPath("$.balance").value(50.00));
        long count = txJpa.findAll().stream()
                .filter(t -> t.getIdempotencyKey().equals("mm-key")).count();
        assertEquals(1, count, "ledger chi co dung 1 but toan cho key nay");
    }

    @Test
    void moneyEndpoints_blankIdempotencyKeyHeader_returns400() throws Exception {
        long id = createWallet("Heidi");
        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1").header("Idempotency-Key", "")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/wallets/" + id + "/withdraw").header("X-User-Id", "user-1").header("Idempotency-Key", "")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void topup_missingIdempotencyKeyHeader_returns400() throws Exception {
        long id = createWallet("Dave");
        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":5.00}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void topup_moreThanTwoDecimals_returns400() throws Exception {
        long id = createWallet("ScaleSue");
        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1").header("Idempotency-Key", "sc-1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":0.005}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("amount must have at most 2 decimal places"));
    }

    @Test
    void topup_twoDecimals_retrySameKey_replaysSameTransaction_andBalanceMatches() throws Exception {
        long id = createWallet("ScaleSam");
        String body = "{\"amount\":10.05}";

        MvcResult first = mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1").header("Idempotency-Key", "sc-retry")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn();
        String firstTxId = first.getResponse().getContentAsString().replaceAll(".*\"id\":(\\d+).*", "$1");

        // retry cung key + cung body -> 200 voi CUNG transaction id (khong 422 do lech scale DB)
        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1").header("Idempotency-Key", "sc-retry")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(Long.parseLong(firstTxId)));

        // balance trong DB khop balanceAfter cua response (khong bi lam tron lech)
        mockMvc.perform(get("/wallets/" + id).header("X-User-Id", "user-1"))
                .andExpect(jsonPath("$.balance").value(10.05));
    }

    @Test
    void topup_negativeAmount_returns400() throws Exception {
        long id = createWallet("Eve");
        mockMvc.perform(post("/wallets/" + id + "/topup").header("X-User-Id", "user-1").header("Idempotency-Key", "n1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\":-5.00}"))
                .andExpect(status().isBadRequest());
    }
}
