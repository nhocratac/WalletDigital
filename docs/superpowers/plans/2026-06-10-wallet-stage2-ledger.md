# Wallet Stage 2 — Ledger + Topup/Withdraw Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Thêm vào `wallet-service` sổ cái bất biến (`wallet_transaction`) + topup/withdraw với idempotency (`Idempotency-Key`) + optimistic locking (`@Version`) + chuyển mapping entity↔domain sang **MapStruct**.

**Architecture:** Giữ Clean Architecture hiện có. Quy tắc tiền tệ (`topup`/`withdraw`/insufficient-funds) sống trong domain `Wallet`. Ledger ghi cùng transaction với cập nhật balance (cùng commit/rollback). Idempotency chốt chặn ở DB bằng `UNIQUE(idempotency_key)`. MapStruct (compile-time, `unmappedTargetPolicy=ERROR`) thay mapping tay — đưa vào đúng lúc số cặp map tăng.

**Tech Stack:** Java 25, Spring Boot 3.4.4, Spring Data JPA, H2, **MapStruct 1.6.3**, JUnit 5.

**Spec gốc:** `docs/superpowers/specs/2026-06-09-multi-tenant-wallet-design.md` (mục 5 mô hình dữ liệu, luồng topup, bảng lỗi). **Phạm vi SP1:** KHÔNG multi-tenant, KHÔNG HMAC/TraceId (stage sau), KHÔNG cổng KYC (SP3).

**Thư mục làm việc:** `wallet-service/` (đã dọn vào monorepo layout).

---

## Cấu trúc file (thay đổi)

```
wallet-service/
├── pom.xml                                          (Modify: + MapStruct)
└── src
    ├── main/java/com/vng/wallet/
    │   ├── domain/
    │   │   ├── Wallet.java                          (Modify: + version, topup(), withdraw())
    │   │   ├── WalletTransaction.java               (Create: record bất biến)
    │   │   ├── InsufficientFundsException.java      (Create)
    │   │   └── WalletRepository.java                (Modify: + 3 method ledger)
    │   ├── application/WalletService.java           (Modify: + topup/withdraw/listTransactions)
    │   └── infrastructure/
    │       ├── persistence/
    │       │   ├── WalletEntity.java                (Modify: + @Version)
    │       │   ├── WalletTransactionEntity.java     (Create: UNIQUE idempotency_key)
    │       │   ├── SpringDataWalletTransactionJpa.java (Create)
    │       │   ├── WalletMapper.java                (Create: MapStruct)
    │       │   └── JpaWalletRepository.java         (Modify: dùng mapper + ledger)
    │       └── web/
    │           ├── WalletController.java            (Modify: + 3 endpoint)
    │           ├── GlobalExceptionHandler.java      (Modify: + 422/409)
    │           └── dto/ (MoneyRequest, TransactionResponse — Create)
    └── test/... (mirror)
```

---

## Task 1: Đưa MapStruct vào — refactor mapping DƯỚI LƯỚI TEST XANH

> Bài học: đổi cơ chế bên trong (mapping tay → MapStruct) mà hành vi không đổi — 13 test hiện có là lưới an toàn. KHÔNG viết test mới ở task này; test cũ pass = refactor đúng.

**Files:**
- Modify: `wallet-service/pom.xml`
- Create: `wallet-service/src/main/java/com/vng/wallet/infrastructure/persistence/WalletMapper.java`
- Modify: `wallet-service/src/main/java/com/vng/wallet/infrastructure/persistence/JpaWalletRepository.java`

- [ ] **Step 1: Chạy test hiện có làm BASELINE**

Run: `cd wallet-service && mvn -q test`
Expected: 13 tests PASS (lưới an toàn trước khi đổi).

- [ ] **Step 2: Thêm MapStruct vào `pom.xml`** — dependency + annotation processor:

```xml
<!-- trong <dependencies> -->
<dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>1.6.3</version>
</dependency>
```

```xml
<!-- trong <build><plugins>, THÊM cấu hình maven-compiler-plugin -->
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.mapstruct</groupId>
                <artifactId>mapstruct-processor</artifactId>
                <version>1.6.3</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

- [ ] **Step 3: Tạo `WalletMapper`**

```java
package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.Wallet;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/**
 * MapStruct sinh code map lúc COMPILE (không reflection — nhanh như viết tay).
 * unmappedTargetPolicy=ERROR: thêm field mới mà quên map -> LỖI BUILD ngay,
 * không thành bug runtime. Đây là lý do chính ta dùng nó khi số field tăng.
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WalletMapper {

    WalletEntity toEntity(Wallet wallet);

    Wallet toDomain(WalletEntity entity);
}
```

- [ ] **Step 4: Sửa `JpaWalletRepository` dùng mapper** (toàn văn file mới)

```java
package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JpaWalletRepository implements WalletRepository {

    private final SpringDataWalletJpa jpa;
    private final WalletMapper mapper;

    public JpaWalletRepository(SpringDataWalletJpa jpa, WalletMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    public Wallet save(Wallet wallet) {
        return mapper.toDomain(jpa.save(mapper.toEntity(wallet)));
    }

    @Override
    public Optional<Wallet> findById(Long id) {
        return jpa.findById(id).map(mapper::toDomain);
    }
}
```

- [ ] **Step 5: Chạy lại TOÀN BỘ test — lưới an toàn phải vẫn xanh**

Run: `cd wallet-service && mvn -q test`
Expected: 13 tests PASS, hành vi y nguyên. (Nếu MapStruct kêu thiếu constructor phù hợp: `WalletEntity` đã có public ctor 3 tham số — MapStruct dùng nó.)

- [ ] **Step 6: Commit** — `git add wallet-service && git commit -m "refactor(wallet): introduce MapStruct mapping under green tests"`

---

## Task 2: Domain — `Wallet.topup()/withdraw()` + `version` + `InsufficientFundsException`

**Files:**
- Modify: `wallet-service/src/main/java/com/vng/wallet/domain/Wallet.java`
- Create: `wallet-service/src/main/java/com/vng/wallet/domain/InsufficientFundsException.java`
- Modify (test): `wallet-service/src/test/java/com/vng/wallet/domain/WalletTest.java`

- [ ] **Step 1: Thêm test thất bại vào `WalletTest`**

```java
// THÊM các test sau vào class WalletTest hiện có:

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
```

LƯU Ý: constructor `Wallet` đổi thành 4 tham số `(id, ownerName, balance, version)` — cập nhật các chỗ gọi 3 tham số trong `WalletTest.rehydrate_keepsGivenValues` (thêm `0L`).

- [ ] **Step 2:** Run `cd wallet-service && mvn -q test -Dtest=WalletTest` → FAIL (compile: chưa có topup/withdraw/ctor 4 tham số).

- [ ] **Step 3: Sửa domain (toàn văn `Wallet.java` mới + exception)**

```java
package com.vng.wallet.domain;

import java.math.BigDecimal;

/**
 * Domain model thuần Java. Quy tắc tiền tệ sống TẠI ĐÂY:
 * - amount phải > 0
 * - không rút quá số dư (InsufficientFundsException)
 * version: optimistic lock, do persistence quản lý (null khi ví mới).
 */
public class Wallet {

    private final Long id;
    private final String ownerName;
    private BigDecimal balance;
    private final Long version;

    public Wallet(Long id, String ownerName, BigDecimal balance, Long version) {
        this.id = id;
        this.ownerName = ownerName;
        this.balance = balance;
        this.version = version;
    }

    public static Wallet createNew(String ownerName) {
        return new Wallet(null, ownerName, BigDecimal.ZERO, null);
    }

    public void topup(BigDecimal amount) {
        requirePositive(amount);
        this.balance = this.balance.add(amount);
    }

    public void withdraw(BigDecimal amount) {
        requirePositive(amount);
        if (balance.compareTo(amount) < 0) {
            throw new InsufficientFundsException(id, balance, amount);
        }
        this.balance = this.balance.subtract(amount);
    }

    private void requirePositive(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
    }

    public Long getId() { return id; }
    public String getOwnerName() { return ownerName; }
    public BigDecimal getBalance() { return balance; }
    public Long getVersion() { return version; }
}
```

```java
package com.vng.wallet.domain;

import java.math.BigDecimal;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(Long walletId, BigDecimal balance, BigDecimal requested) {
        super("Insufficient funds in wallet " + walletId
                + ": balance=" + balance + ", requested=" + requested);
    }
}
```

- [ ] **Step 4: Sửa các chỗ vỡ do ctor 4 tham số** — `WalletControllerTest.TestStubConfig` (stub `save`/`findById`: thêm `0L`), `WalletServiceTest.InMemoryWalletRepository.save` (thêm `wallet.getVersion()`), `JpaWalletRepositoryTest` (nếu có gọi ctor). MapStruct sẽ báo lỗi build `version` chưa map sang `WalletEntity` (ERROR policy hoạt động đúng!) — tạm thời qua Task 5 mới thêm `@Version` vào entity, nên Ở TASK NÀY thêm field `version` vào `WalletEntity` (ctor 4 tham số + getter, CHƯA cần annotation `@Version` — Task 5 sẽ gắn).

- [ ] **Step 5:** Run `cd wallet-service && mvn -q test` → PASS toàn bộ (13 cũ đã chỉnh + 5 mới = 18).
- [ ] **Step 6:** `git add wallet-service && git commit -m "feat(wallet): domain money rules topup/withdraw + version field"`

---

## Task 3: Domain — `WalletTransaction` + mở rộng port

**Files:**
- Create: `wallet-service/src/main/java/com/vng/wallet/domain/WalletTransaction.java`
- Modify: `wallet-service/src/main/java/com/vng/wallet/domain/WalletRepository.java`

- [ ] **Step 1: Record bất biến**

```java
package com.vng.wallet.domain;

import java.math.BigDecimal;
import java.time.Instant;

/** Một bút toán trong sổ cái — BẤT BIẾN. balanceAfter = số dư SAU bút toán (đối soát). */
public record WalletTransaction(
        Long id,                 // null trước khi lưu, DB cấp
        Long walletId,
        Type type,
        BigDecimal amount,
        String idempotencyKey,
        BigDecimal balanceAfter,
        Instant createdAt
) {
    public enum Type { TOPUP, WITHDRAW }
}
```

- [ ] **Step 2: Mở rộng port `WalletRepository` (toàn văn mới)**

```java
package com.vng.wallet.domain;

import java.util.List;
import java.util.Optional;

public interface WalletRepository {
    Wallet save(Wallet wallet);
    Optional<Wallet> findById(Long id);

    WalletTransaction saveTransaction(WalletTransaction transaction);
    Optional<WalletTransaction> findTransactionByIdempotencyKey(String idempotencyKey);
    List<WalletTransaction> listTransactions(Long walletId);
}
```

- [ ] **Step 3:** Run `mvn -q compile` → FAIL ở các class cài port (fake/stub/adapter chưa có 3 method). Thêm cài đặt vào: `WalletServiceTest.InMemoryWalletRepository` (Map theo idempotencyKey + List), `WalletControllerTest.TestStubConfig` (in-memory tương tự, đơn giản), `JpaWalletRepository` (TẠM `throw new UnsupportedOperationException("Task 5")` cho 3 method mới — sẽ cài thật ở Task 5).
- [ ] **Step 4:** Run `mvn -q test` → PASS (18).
- [ ] **Step 5:** `git add wallet-service && git commit -m "feat(wallet): ledger record + repository port extension"`

---

## Task 4: Application — topup/withdraw idempotent + listTransactions

**Files:**
- Modify: `wallet-service/src/main/java/com/vng/wallet/application/WalletService.java`
- Modify (test): `wallet-service/src/test/java/com/vng/wallet/application/WalletServiceTest.java`

- [ ] **Step 1: Thêm test thất bại vào `WalletServiceTest`** (fake repo đã có 3 method từ Task 3)

```java
@Test
void topup_appendsLedgerAndUpdatesBalance() {
    Wallet w = service.createWallet("Alice");

    WalletTransaction tx = service.topup(w.getId(), new BigDecimal("50.00"), "key-1");

    assertEquals(WalletTransaction.Type.TOPUP, tx.type());
    assertEquals(0, new BigDecimal("50.00").compareTo(tx.balanceAfter()));
    assertEquals(0, new BigDecimal("50.00").compareTo(service.getWallet(w.getId()).getBalance()));
    assertEquals(1, service.listTransactions(w.getId()).size());
}

@Test
void topup_sameIdempotencyKeyTwice_appliesOnce() {
    Wallet w = service.createWallet("Alice");
    WalletTransaction first = service.topup(w.getId(), new BigDecimal("50.00"), "key-dup");

    WalletTransaction second = service.topup(w.getId(), new BigDecimal("50.00"), "key-dup");

    assertEquals(first.id(), second.id(), "trả lại bút toán CŨ, không tạo mới");
    assertEquals(0, new BigDecimal("50.00").compareTo(service.getWallet(w.getId()).getBalance()),
            "balance chỉ cộng MỘT lần");
    assertEquals(1, service.listTransactions(w.getId()).size());
}

@Test
void withdraw_appendsLedger() {
    Wallet w = service.createWallet("Bob");
    service.topup(w.getId(), new BigDecimal("100.00"), "k1");

    WalletTransaction tx = service.withdraw(w.getId(), new BigDecimal("30.00"), "k2");

    assertEquals(WalletTransaction.Type.WITHDRAW, tx.type());
    assertEquals(0, new BigDecimal("70.00").compareTo(tx.balanceAfter()));
    assertEquals(2, service.listTransactions(w.getId()).size());
}

@Test
void withdraw_insufficient_throwsAndNoLedgerEntry() {
    Wallet w = service.createWallet("Carol");

    assertThrows(InsufficientFundsException.class,
            () -> service.withdraw(w.getId(), new BigDecimal("1.00"), "k3"));
    assertEquals(0, service.listTransactions(w.getId()).size(), "thất bại -> KHÔNG có bút toán");
}
```

- [ ] **Step 2:** Run `mvn -q test -Dtest=WalletServiceTest` → FAIL.

- [ ] **Step 3: Sửa `WalletService` (toàn văn mới)**

```java
package com.vng.wallet.application;

import com.vng.wallet.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    @Transactional
    public Wallet createWallet(String ownerName) {
        return walletRepository.save(Wallet.createNew(ownerName));
    }

    @Transactional(readOnly = true)
    public Wallet getWallet(Long id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> new WalletNotFoundException(id));
    }

    @Transactional
    public WalletTransaction topup(Long walletId, BigDecimal amount, String idempotencyKey) {
        return applyMoneyOperation(walletId, amount, idempotencyKey, WalletTransaction.Type.TOPUP);
    }

    @Transactional
    public WalletTransaction withdraw(Long walletId, BigDecimal amount, String idempotencyKey) {
        return applyMoneyOperation(walletId, amount, idempotencyKey, WalletTransaction.Type.WITHDRAW);
    }

    @Transactional(readOnly = true)
    public List<WalletTransaction> listTransactions(Long walletId) {
        getWallet(walletId); // 404 nếu ví không tồn tại
        return walletRepository.listTransactions(walletId);
    }

    /**
     * Cả balance (cache) + bút toán (sổ cái) ghi trong CÙNG transaction —
     * cùng commit hoặc cùng rollback. Idempotency: key đã có -> trả bút toán cũ.
     */
    private WalletTransaction applyMoneyOperation(Long walletId, BigDecimal amount,
                                                  String idempotencyKey, WalletTransaction.Type type) {
        var existing = walletRepository.findTransactionByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get(); // retry -> không áp lần hai
        }
        Wallet wallet = getWallet(walletId);
        if (type == WalletTransaction.Type.TOPUP) {
            wallet.topup(amount);
        } else {
            wallet.withdraw(amount); // có thể ném InsufficientFunds -> rollback, không ghi gì
        }
        Wallet saved = walletRepository.save(wallet);
        return walletRepository.saveTransaction(new WalletTransaction(
                null, walletId, type, amount, idempotencyKey, saved.getBalance(), Instant.now()));
    }
}
```

- [ ] **Step 4:** Run `mvn -q test -Dtest=WalletServiceTest` → PASS (7 = 3 cũ + 4 mới).
- [ ] **Step 5:** `git add wallet-service && git commit -m "feat(wallet): idempotent topup/withdraw use cases with ledger"`

---

## Task 5: Persistence — `@Version` + `WalletTransactionEntity` + MapStruct cho transaction

**Files:**
- Modify: `wallet-service/src/main/java/com/vng/wallet/infrastructure/persistence/WalletEntity.java` (gắn `@Version` lên field version đã thêm ở Task 2)
- Create: `.../persistence/WalletTransactionEntity.java`, `.../persistence/SpringDataWalletTransactionJpa.java`
- Modify: `.../persistence/WalletMapper.java` (+ 2 method transaction), `.../persistence/JpaWalletRepository.java` (cài 3 method thật)
- Modify (test): `wallet-service/src/test/java/com/vng/wallet/infrastructure/persistence/JpaWalletRepositoryTest.java`

- [ ] **Step 1: Thêm test thất bại vào `JpaWalletRepositoryTest`**

```java
@Test
void transactionRoundTrip_andIdempotencyLookup() {
    Wallet w = repository.save(Wallet.createNew("Alice"));
    WalletTransaction tx = repository.saveTransaction(new WalletTransaction(
            null, w.getId(), WalletTransaction.Type.TOPUP,
            new BigDecimal("50.00"), "key-abc", new BigDecimal("50.00"), Instant.now()));
    em.flush(); em.clear();

    assertNotNull(tx.id());
    var found = repository.findTransactionByIdempotencyKey("key-abc");
    assertTrue(found.isPresent());
    assertEquals(tx.id(), found.get().id());
    assertEquals(0, new BigDecimal("50.00").compareTo(found.get().balanceAfter()));
    assertEquals(1, repository.listTransactions(w.getId()).size());
}

@Test
void duplicateIdempotencyKey_violatesDbConstraint() {
    Wallet w = repository.save(Wallet.createNew("Bob"));
    repository.saveTransaction(new WalletTransaction(null, w.getId(),
            WalletTransaction.Type.TOPUP, BigDecimal.ONE, "dup-key", BigDecimal.ONE, Instant.now()));

    assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> {
        repository.saveTransaction(new WalletTransaction(null, w.getId(),
                WalletTransaction.Type.TOPUP, BigDecimal.ONE, "dup-key", BigDecimal.ONE, Instant.now()));
        txJpa.flush(); // flush qua proxy Spring Data để có exception translation
    });
}
```

(Thêm `@Autowired SpringDataWalletTransactionJpa txJpa;` và import `Instant`, `WalletTransaction` vào test. Cần `em.flush()` sau saveTransaction đầu để ghi xuống DB trước.)

- [ ] **Step 2:** Run → FAIL (entity/method chưa có).

- [ ] **Step 3: `WalletEntity` — gắn `@Version`** (field version đã tồn tại từ Task 2; chỉ thêm annotation `@jakarta.persistence.Version` lên nó).

- [ ] **Step 4: Entity + Spring Data mới**

```java
package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.WalletTransaction;
import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "wallet_transaction",
       uniqueConstraints = @UniqueConstraint(columnNames = "idempotencyKey")) // chốt idempotency tầng DB
public class WalletTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long walletId;
    @Enumerated(EnumType.STRING)
    private WalletTransaction.Type type;
    private BigDecimal amount;
    private String idempotencyKey;
    private BigDecimal balanceAfter;
    private Instant createdAt;

    protected WalletTransactionEntity() {}

    public WalletTransactionEntity(Long id, Long walletId, WalletTransaction.Type type,
                                   BigDecimal amount, String idempotencyKey,
                                   BigDecimal balanceAfter, Instant createdAt) {
        this.id = id; this.walletId = walletId; this.type = type; this.amount = amount;
        this.idempotencyKey = idempotencyKey; this.balanceAfter = balanceAfter; this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public Long getWalletId() { return walletId; }
    public WalletTransaction.Type getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public Instant getCreatedAt() { return createdAt; }
}
```

```java
package com.vng.wallet.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SpringDataWalletTransactionJpa extends JpaRepository<WalletTransactionEntity, Long> {
    Optional<WalletTransactionEntity> findByIdempotencyKey(String idempotencyKey);
    List<WalletTransactionEntity> findByWalletIdOrderByCreatedAtAsc(Long walletId);
}
```

- [ ] **Step 5: Mở rộng `WalletMapper` + cài `JpaWalletRepository` thật**

```java
// THÊM vào interface WalletMapper:
WalletTransactionEntity toEntity(com.vng.wallet.domain.WalletTransaction tx);
com.vng.wallet.domain.WalletTransaction toDomain(WalletTransactionEntity entity);
```

```java
// JpaWalletRepository: thêm field + cài 3 method (thay UnsupportedOperationException)
private final SpringDataWalletTransactionJpa txJpa; // + tham số constructor

@Override
public WalletTransaction saveTransaction(WalletTransaction transaction) {
    return mapper.toDomain(txJpa.save(mapper.toEntity(transaction)));
}

@Override
public Optional<WalletTransaction> findTransactionByIdempotencyKey(String idempotencyKey) {
    return txJpa.findByIdempotencyKey(idempotencyKey).map(mapper::toDomain);
}

@Override
public List<WalletTransaction> listTransactions(Long walletId) {
    return txJpa.findByWalletIdOrderByCreatedAtAsc(walletId).stream().map(mapper::toDomain).toList();
}
```

- [ ] **Step 6:** Run `cd wallet-service && mvn -q test` → PASS toàn bộ.
- [ ] **Step 7:** `git add wallet-service && git commit -m "feat(wallet): ledger persistence + @Version + MapStruct transaction mapping"`

---

## Task 6: Web — endpoints + DTO + 422/409

**Files:**
- Create: `.../web/dto/MoneyRequest.java`, `.../web/dto/TransactionResponse.java`
- Modify: `.../web/WalletController.java`, `.../web/GlobalExceptionHandler.java`

- [ ] **Step 1: DTOs**

```java
package com.vng.wallet.infrastructure.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record MoneyRequest(@NotNull @Positive(message = "amount must be positive") BigDecimal amount) {}
```

```java
package com.vng.wallet.infrastructure.web.dto;

import com.vng.wallet.domain.WalletTransaction;
import java.math.BigDecimal;
import java.time.Instant;

public record TransactionResponse(Long id, Long walletId, String type, BigDecimal amount,
                                  BigDecimal balanceAfter, Instant createdAt) {
    public static TransactionResponse from(WalletTransaction tx) {
        return new TransactionResponse(tx.id(), tx.walletId(), tx.type().name(),
                tx.amount(), tx.balanceAfter(), tx.createdAt());
    }
}
```

- [ ] **Step 2: Thêm 3 endpoint vào `WalletController`**

```java
// THÊM imports: MoneyRequest, TransactionResponse, RequestHeader, List

@PostMapping("/{id}/topup")
public TransactionResponse topup(@PathVariable Long id,
                                 @RequestHeader("Idempotency-Key") String idempotencyKey,
                                 @Valid @RequestBody MoneyRequest request) {
    return TransactionResponse.from(walletService.topup(id, request.amount(), idempotencyKey));
}

@PostMapping("/{id}/withdraw")
public TransactionResponse withdraw(@PathVariable Long id,
                                    @RequestHeader("Idempotency-Key") String idempotencyKey,
                                    @Valid @RequestBody MoneyRequest request) {
    return TransactionResponse.from(walletService.withdraw(id, request.amount(), idempotencyKey));
}

@GetMapping("/{id}/transactions")
public List<TransactionResponse> transactions(@PathVariable Long id) {
    return walletService.listTransactions(id).stream().map(TransactionResponse::from).toList();
}
```

- [ ] **Step 3: Thêm handler vào `GlobalExceptionHandler`**

```java
// THÊM imports: InsufficientFundsException, OptimisticLockingFailureException,
//               MissingRequestHeaderException, MethodArgumentNotValidException (nếu chưa có)

@ExceptionHandler(InsufficientFundsException.class)
public ResponseEntity<Map<String, String>> insufficient(InsufficientFundsException ex) {
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY) // 422: tìm thấy nhưng vi phạm quy tắc
            .body(Map.of("error", ex.getMessage()));
}

@ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
public ResponseEntity<Map<String, String>> lockConflict(Exception ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT) // 409: đua nhau cập nhật, retry
            .body(Map.of("error", "Concurrent update, please retry"));
}

@ExceptionHandler(org.springframework.web.bind.MissingRequestHeaderException.class)
public ResponseEntity<Map<String, String>> missingHeader(Exception ex) {
    return ResponseEntity.badRequest().body(Map.of("error", "Missing required header: Idempotency-Key"));
}

@ExceptionHandler(IllegalArgumentException.class)
public ResponseEntity<Map<String, String>> badArgument(IllegalArgumentException ex) {
    return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
}
```

- [ ] **Step 4:** Run `mvn -q compile` → SUCCESS.
- [ ] **Step 5:** `git add wallet-service && git commit -m "feat(wallet): topup/withdraw/transactions endpoints + 422/409 mapping"`

---

## Task 7: Integration test — idempotency qua HTTP + 422 + luồng đầy đủ

**Files:**
- Create: `wallet-service/src/test/java/com/vng/wallet/WalletLedgerIntegrationTest.java`

- [ ] **Step 1: Viết test (full context + MockMvc + H2 thật)**

```java
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
```

- [ ] **Step 2:** Run `cd wallet-service && mvn -q test -Dtest=WalletLedgerIntegrationTest` → PASS (5 tests).
- [ ] **Step 3:** Run TOÀN BỘ: `mvn -q test` → PASS hết.
- [ ] **Step 4:** `git add wallet-service && git commit -m "test(wallet): ledger integration — HTTP idempotency, 422, full flow"`

---

## Task 8: Smoke test thật bằng curl

- [ ] **Step 1:** Terminal 1: `cd wallet-service && mvn -q spring-boot:run`. Đợi `Started WalletApplication`. Terminal 2:

```bash
curl -s -X POST localhost:8080/wallets -H 'Content-Type: application/json' -d '{"ownerName":"Alice"}'
curl -s -X POST localhost:8080/wallets/1/topup -H 'Content-Type: application/json' -H 'Idempotency-Key: a1' -d '{"amount":100}'
curl -s -X POST localhost:8080/wallets/1/topup -H 'Content-Type: application/json' -H 'Idempotency-Key: a1' -d '{"amount":100}'   # lần 2: balance vẫn 100
curl -s -X POST localhost:8080/wallets/1/withdraw -H 'Content-Type: application/json' -H 'Idempotency-Key: a2' -d '{"amount":30}'
curl -s localhost:8080/wallets/1/transactions
curl -s -X POST localhost:8080/wallets/1/withdraw -H 'Content-Type: application/json' -H 'Idempotency-Key: a3' -d '{"amount":999}' -w '\n[%{http_code}]\n'  # 422
```

Expected: topup lần 2 trả `balanceAfter:100` (không 200); transactions có 2 dòng; lệnh cuối `[422]`. Dừng app bằng kill tiến trình giữ cổng 8080 (KHÔNG chỉ pkill maven — nhớ bài orphan process).

- [ ] **Step 2:** `git add -A && git commit -m "feat(wallet): Stage 2 ledger complete"` (nếu có thay đổi còn lại).

---

## Định nghĩa "Done"

- `cd wallet-service && mvn -q test` xanh toàn bộ (~28+ tests).
- Domain `Wallet` chứa quy tắc tiền; `domain/` không import Spring/JPA/MapStruct.
- MapStruct map cả Wallet lẫn WalletTransaction; build fail nếu quên map field (ERROR policy).
- Idempotency: cùng key → 1 bút toán trong DB (cả unit, integration, curl).
- 422 cho insufficient, 400 cho thiếu header/amount xấu, `@Version` có trên wallet.

## Bước kế tiếp (plan riêng)
- SP3: cổng KYC trên withdraw (sync + circuit breaker fail-closed + cache TTL + Kafka kyc.revoked).
