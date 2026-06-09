package com.vng.wallet;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The Repository is the "door" to the database for Wallets.
 *
 * Here's the magical part: we only DECLARE this interface — we write NO code.
 * Spring Data JPA generates the implementation for us at runtime.
 * Just by extending JpaRepository<Wallet, Long> we already get, for free:
 *    save(wallet), findById(id), findAll(), deleteById(id), ...
 *
 * (Wallet = the type we store, Long = the type of its id.)
 *
 * ARCHITECT NOTE: this is the "Repository pattern" — it isolates database
 * access behind an interface. Our business code never writes SQL directly,
 * so we could swap H2 for MySQL later without changing this file at all.
 */
public interface WalletRepository extends JpaRepository<Wallet, Long> {
}
