package com.vng.wallet.domain;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class WalletTest {

    @Test
    void createNew_startsWithZeroBalance() {
        Wallet wallet = Wallet.createNew("user-1", "Alice");

        assertNull(wallet.getId(), "ví mới chưa có id (DB cấp sau)");
        assertEquals("Alice", wallet.getOwnerName());
        assertEquals(0, BigDecimal.ZERO.compareTo(wallet.getBalance()), "số dư khởi tạo = 0");
    }

    @Test
    void createNew_bindsUserId() {
        Wallet w = Wallet.createNew("user-1", "Alice");
        assertEquals("user-1", w.getUserId());
        assertEquals("Alice", w.getOwnerName());
        assertEquals(0, BigDecimal.ZERO.compareTo(w.getBalance()));
    }

    @Test
    void rehydrate_keepsGivenValues() {
        Wallet wallet = new Wallet(7L, "user-1", "Bob", new BigDecimal("150.00"), BigDecimal.ZERO, 0L);

        assertEquals(7L, wallet.getId());
        assertEquals("user-1", wallet.getUserId());
        assertEquals("Bob", wallet.getOwnerName());
        assertEquals(0, new BigDecimal("150.00").compareTo(wallet.getBalance()));
    }

    @Test
    void topup_increasesBalance() {
        Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("100.00"), BigDecimal.ZERO, 0L);
        w.topup(new BigDecimal("50.00"));
        assertEquals(0, new BigDecimal("150.00").compareTo(w.getBalance()));
    }

    @Test
    void withdraw_decreasesBalance() {
        Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("100.00"), BigDecimal.ZERO, 0L);
        w.withdraw(new BigDecimal("40.00"));
        assertEquals(0, new BigDecimal("60.00").compareTo(w.getBalance()));
    }

    @Test
    void withdraw_exactBalance_succeedsToZero() {
        Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("30.00"), BigDecimal.ZERO, 0L);
        w.withdraw(new BigDecimal("30.00"));
        assertEquals(0, BigDecimal.ZERO.compareTo(w.getBalance()), "rút đúng số dư phải hợp lệ, balance về 0");
    }

    @Test
    void withdraw_exactBalance_differentScale_succeedsToZero() {
        Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("30.00"), BigDecimal.ZERO, 0L);
        w.withdraw(new BigDecimal("30"));
        assertEquals(0, BigDecimal.ZERO.compareTo(w.getBalance()), "so sánh không phụ thuộc scale ('30' == '30.00')");
    }

    @Test
    void withdraw_insufficientFunds_throws() {
        Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("30.00"), BigDecimal.ZERO, 0L);
        assertThrows(InsufficientFundsException.class, () -> w.withdraw(new BigDecimal("30.01")));
        assertEquals(0, new BigDecimal("30.00").compareTo(w.getBalance()), "balance KHÔNG đổi khi bị từ chối");
    }

    @Test
    void topup_nonPositiveAmount_throws() {
        Wallet w = new Wallet(1L, "user-1", "Alice", BigDecimal.ZERO, BigDecimal.ZERO, 0L);
        assertThrows(IllegalArgumentException.class, () -> w.topup(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> w.topup(new BigDecimal("-5")));
    }

    @Test
    void withdraw_nonPositiveAmount_throws() {
        Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("10"), BigDecimal.ZERO, 0L);
        assertThrows(IllegalArgumentException.class, () -> w.withdraw(BigDecimal.ZERO));
    }

    // ---- SP4: escrow (available/held) ----

    @Test
    void reserve_reducesAvailableNotBalance() {
        Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("100"), new BigDecimal("0"), 0L);
        w.reserve(new BigDecimal("30"));
        assertEquals(0, new BigDecimal("100").compareTo(w.getBalance()), "total không đổi");
        assertEquals(0, new BigDecimal("30").compareTo(w.getHeld()));
        assertEquals(0, new BigDecimal("70").compareTo(w.available()));
    }

    @Test
    void reserve_rejectsWhenInsufficientAvailable() {
        Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("100"), new BigDecimal("80"), 0L); // held 80
        assertThrows(InsufficientFundsException.class, () -> w.reserve(new BigDecimal("30"))); // available 20
    }

    @Test
    void settle_movesOutOfTotalAndHeld() {
        Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("100"), new BigDecimal("30"), 0L);
        w.settle(new BigDecimal("30"));
        assertEquals(0, new BigDecimal("70").compareTo(w.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(w.getHeld()));
    }

    @Test
    void release_returnsHeldToAvailable() {
        Wallet w = new Wallet(1L, "user-1", "Alice", new BigDecimal("100"), new BigDecimal("30"), 0L);
        w.release(new BigDecimal("30")); // refund
        assertEquals(0, new BigDecimal("100").compareTo(w.getBalance()));
        assertEquals(0, BigDecimal.ZERO.compareTo(w.getHeld()));
        assertEquals(0, new BigDecimal("100").compareTo(w.available()));
    }
}
