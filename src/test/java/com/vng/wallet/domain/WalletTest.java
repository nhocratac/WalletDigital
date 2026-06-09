package com.vng.wallet.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class WalletTest {

    @Test
    void createNew_startsWithZeroBalance() {
        Wallet wallet = Wallet.createNew("Alice");

        assertNull(wallet.getId(), "ví mới chưa có id (DB cấp sau)");
        assertEquals("Alice", wallet.getOwnerName());
        assertEquals(0, BigDecimal.ZERO.compareTo(wallet.getBalance()), "số dư khởi tạo = 0");
    }

    @Test
    void rehydrate_keepsGivenValues() {
        Wallet wallet = new Wallet(7L, "Bob", new BigDecimal("150.00"));

        assertEquals(7L, wallet.getId());
        assertEquals("Bob", wallet.getOwnerName());
        assertEquals(0, new BigDecimal("150.00").compareTo(wallet.getBalance()));
    }
}
