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
        Wallet wallet = new Wallet(7L, "Bob", new BigDecimal("150.00"), 0L);

        assertEquals(7L, wallet.getId());
        assertEquals("Bob", wallet.getOwnerName());
        assertEquals(0, new BigDecimal("150.00").compareTo(wallet.getBalance()));
    }

    @Test
    void topup_increasesBalance() {
        Wallet w = new Wallet(1L, "Alice", new BigDecimal("100.00"), 0L);
        w.topup(new BigDecimal("50.00"));
        assertEquals(0, new BigDecimal("150.00").compareTo(w.getBalance()));
    }

    @Test
    void withdraw_decreasesBalance() {
        Wallet w = new Wallet(1L, "Alice", new BigDecimal("100.00"), 0L);
        w.withdraw(new BigDecimal("40.00"));
        assertEquals(0, new BigDecimal("60.00").compareTo(w.getBalance()));
    }

    @Test
    void withdraw_insufficientFunds_throws() {
        Wallet w = new Wallet(1L, "Alice", new BigDecimal("30.00"), 0L);
        assertThrows(InsufficientFundsException.class, () -> w.withdraw(new BigDecimal("30.01")));
        assertEquals(0, new BigDecimal("30.00").compareTo(w.getBalance()), "balance KHÔNG đổi khi bị từ chối");
    }

    @Test
    void topup_nonPositiveAmount_throws() {
        Wallet w = new Wallet(1L, "Alice", BigDecimal.ZERO, 0L);
        assertThrows(IllegalArgumentException.class, () -> w.topup(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> w.topup(new BigDecimal("-5")));
    }

    @Test
    void withdraw_nonPositiveAmount_throws() {
        Wallet w = new Wallet(1L, "Alice", new BigDecimal("10"), 0L);
        assertThrows(IllegalArgumentException.class, () -> w.withdraw(BigDecimal.ZERO));
    }
}
