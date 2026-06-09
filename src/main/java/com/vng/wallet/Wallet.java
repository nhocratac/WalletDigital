package com.vng.wallet;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.math.BigDecimal;

/**
 * A Wallet is our core "domain object" — the thing this service is about.
 *
 * @Entity tells JPA: "this class maps to a database table".
 * Each field becomes a column; each saved Wallet becomes a row.
 *
 * ARCHITECT NOTE: notice the balance is a BigDecimal, NOT a double.
 * Never use double/float for money — they have rounding errors
 * (0.1 + 0.2 != 0.3 in floating point!). For money, always use BigDecimal.
 * This is the kind of decision that separates a careful engineer from a buggy one.
 */
@Entity
public class Wallet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY) // DB assigns the id automatically
    private Long id;

    private String ownerName;

    private BigDecimal balance;

    // JPA requires a no-argument constructor (it builds objects from DB rows).
    protected Wallet() {
    }

    public Wallet(String ownerName, BigDecimal balance) {
        this.ownerName = ownerName;
        this.balance = balance;
    }

    public Long getId() {
        return id;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public void setBalance(BigDecimal balance) {
        this.balance = balance;
    }
}
