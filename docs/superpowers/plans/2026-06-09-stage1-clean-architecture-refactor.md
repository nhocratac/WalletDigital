# Stage 1 — Refactor Wallet sang Clean Architecture — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tổ chức lại service ví hiện tại (tạo ví + xem ví) theo Clean Architecture — tách `domain` / `application` / `infrastructure` — mà KHÔNG đổi hành vi bên ngoài (API giữ nguyên).

**Architecture:** Lõi `domain` thuần Java (không Spring/JPA). `application` chứa use case, phụ thuộc một *port* (interface). `infrastructure` chứa adapter JPA (map giữa domain `Wallet` và `WalletEntity`), web controller, exception handler. Mũi tên phụ thuộc chỉ trỏ vào trong.

**Tech Stack:** Java 21, Spring Boot 3.4.4, Spring Data JPA, H2 (in-memory), JUnit 5, Maven.

**Phạm vi plan này:** CHỈ Bước 1 (refactor cấu trúc, giữ 2 endpoint `POST /wallets` + `GET /wallets/{id}`). Ledger, multi-tenant, HMAC, TraceId sẽ ở các plan sau.

---

## Cấu trúc file (sau refactor)

```
src/main/java/com/vng/wallet/
├── WalletApplication.java                         (giữ nguyên)
├── domain/
│   ├── Wallet.java                                (Create — POJO thuần, không JPA)
│   ├── WalletRepository.java                      (Create — PORT interface)
│   └── WalletNotFoundException.java               (Move từ gốc)
├── application/
│   └── WalletService.java                         (Move + sửa: dùng port)
└── infrastructure/
    ├── persistence/
    │   ├── WalletEntity.java                       (Create — @Entity JPA)
    │   ├── SpringDataWalletJpa.java                (Create — extends JpaRepository)
    │   └── JpaWalletRepository.java                (Create — ADAPTER implements port)
    └── web/
        ├── WalletController.java                   (Move + sửa import)
        ├── GlobalExceptionHandler.java             (Move + sửa import)
        └── dto/
            ├── CreateWalletRequest.java            (Move)
            └── WalletResponse.java                 (Move + sửa import)
```

**Xoá cuối cùng (Task 7):** các file cũ ở gốc `com/vng/wallet/`: `Wallet.java`, `WalletRepository.java`, `WalletService.java`, `WalletController.java`, `GlobalExceptionHandler.java`, `dto/CreateWalletRequest.java`, `dto/WalletResponse.java`.

---

## Task 1: Domain `Wallet` (POJO thuần, không JPA)

**Files:**
- Create: `src/main/java/com/vng/wallet/domain/Wallet.java`
- Test: `src/test/java/com/vng/wallet/domain/WalletTest.java`

- [ ] **Step 1: Viết test thất bại**

```java
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
```

- [ ] **Step 2: Chạy test để xác nhận FAIL**

Run: `mvn -q test -Dtest=WalletTest`
Expected: FAIL — biên dịch lỗi vì `com.vng.wallet.domain.Wallet` chưa tồn tại.

- [ ] **Step 3: Viết code tối thiểu cho test pass**

```java
package com.vng.wallet.domain;

import java.math.BigDecimal;

/**
 * Domain model thuần Java — KHÔNG import Spring hay JPA.
 * Đây là lõi nghiệp vụ; có thể copy sang project khác vẫn biên dịch được.
 */
public class Wallet {

    private final Long id;          // null khi ví mới; DB cấp id sau
    private final String ownerName;
    private BigDecimal balance;

    public Wallet(Long id, String ownerName, BigDecimal balance) {
        this.id = id;
        this.ownerName = ownerName;
        this.balance = balance;
    }

    /** Tạo ví mới: chưa có id, số dư = 0 (quy tắc nghiệp vụ). */
    public static Wallet createNew(String ownerName) {
        return new Wallet(null, ownerName, BigDecimal.ZERO);
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
}
```

- [ ] **Step 4: Chạy test để xác nhận PASS**

Run: `mvn -q test -Dtest=WalletTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vng/wallet/domain/Wallet.java src/test/java/com/vng/wallet/domain/WalletTest.java
git commit -m "refactor: add pure-domain Wallet model"
```

---

## Task 2: Port `WalletRepository` + `WalletNotFoundException` (trong domain)

**Files:**
- Create: `src/main/java/com/vng/wallet/domain/WalletRepository.java`
- Move:   `src/main/java/com/vng/wallet/domain/WalletNotFoundException.java` (từ `com/vng/wallet/WalletNotFoundException.java`)

- [ ] **Step 1: Tạo port interface** (interface không cần test riêng)

```java
package com.vng.wallet.domain;

import java.util.Optional;

/**
 * PORT — interface do tầng nghiệp vụ định nghĩa. KHÔNG nói gì về JPA/SQL.
 * Adapter ở infrastructure sẽ cài đặt nó.
 */
public interface WalletRepository {
    Wallet save(Wallet wallet);
    Optional<Wallet> findById(Long id);
}
```

- [ ] **Step 2: Tạo exception trong domain**

```java
package com.vng.wallet.domain;

public class WalletNotFoundException extends RuntimeException {
    public WalletNotFoundException(Long id) {
        super("Wallet not found with id: " + id);
    }
}
```

- [ ] **Step 3: Biên dịch để chắc không lỗi**

Run: `mvn -q compile`
Expected: BUILD SUCCESS (file cũ ở gốc vẫn còn, sẽ xoá ở Task 7 — chấp nhận có 2 class cùng tên khác package tạm thời).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/vng/wallet/domain/WalletRepository.java src/main/java/com/vng/wallet/domain/WalletNotFoundException.java
git commit -m "refactor: add WalletRepository port + domain exception"
```

---

## Task 3: `WalletService` (application) dùng port + test với repository giả

**Files:**
- Create: `src/main/java/com/vng/wallet/application/WalletService.java`
- Test:   `src/test/java/com/vng/wallet/application/WalletServiceTest.java`

- [ ] **Step 1: Viết test thất bại (dùng fake repository in-memory)**

```java
package com.vng.wallet.application;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletNotFoundException;
import com.vng.wallet.domain.WalletRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class WalletServiceTest {

    /** Fake repository — cài port bằng HashMap, KHÔNG cần DB thật. */
    static class InMemoryWalletRepository implements WalletRepository {
        private final Map<Long, Wallet> store = new HashMap<>();
        private final AtomicLong seq = new AtomicLong(0);

        @Override
        public Wallet save(Wallet wallet) {
            Long id = wallet.getId() != null ? wallet.getId() : seq.incrementAndGet();
            Wallet saved = new Wallet(id, wallet.getOwnerName(), wallet.getBalance());
            store.put(id, saved);
            return saved;
        }

        @Override
        public Optional<Wallet> findById(Long id) {
            return Optional.ofNullable(store.get(id));
        }
    }

    private final WalletService service = new WalletService(new InMemoryWalletRepository());

    @Test
    void createWallet_savesWithZeroBalanceAndId() {
        Wallet created = service.createWallet("Alice");

        assertNotNull(created.getId(), "sau khi lưu phải có id");
        assertEquals("Alice", created.getOwnerName());
        assertEquals(0, BigDecimal.ZERO.compareTo(created.getBalance()));
    }

    @Test
    void getWallet_returnsSavedWallet() {
        Wallet created = service.createWallet("Bob");

        Wallet found = service.getWallet(created.getId());

        assertEquals(created.getId(), found.getId());
        assertEquals("Bob", found.getOwnerName());
    }

    @Test
    void getWallet_throwsWhenMissing() {
        assertThrows(WalletNotFoundException.class, () -> service.getWallet(999L));
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận FAIL**

Run: `mvn -q test -Dtest=WalletServiceTest`
Expected: FAIL — `com.vng.wallet.application.WalletService` chưa tồn tại.

- [ ] **Step 3: Viết WalletService**

```java
package com.vng.wallet.application;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletNotFoundException;
import com.vng.wallet.domain.WalletRepository;
import org.springframework.stereotype.Service;

/**
 * USE CASES — điều phối nghiệp vụ. Phụ thuộc PORT (WalletRepository), không biết JPA.
 */
@Service
public class WalletService {

    private final WalletRepository walletRepository;

    public WalletService(WalletRepository walletRepository) {
        this.walletRepository = walletRepository;
    }

    public Wallet createWallet(String ownerName) {
        return walletRepository.save(Wallet.createNew(ownerName));
    }

    public Wallet getWallet(Long id) {
        return walletRepository.findById(id)
                .orElseThrow(() -> new WalletNotFoundException(id));
    }
}
```

- [ ] **Step 4: Chạy test để xác nhận PASS**

Run: `mvn -q test -Dtest=WalletServiceTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vng/wallet/application/WalletService.java src/test/java/com/vng/wallet/application/WalletServiceTest.java
git commit -m "refactor: WalletService use case depends on port"
```

---

## Task 4: Adapter JPA — `WalletEntity` + `SpringDataWalletJpa` + `JpaWalletRepository`

**Files:**
- Create: `src/main/java/com/vng/wallet/infrastructure/persistence/WalletEntity.java`
- Create: `src/main/java/com/vng/wallet/infrastructure/persistence/SpringDataWalletJpa.java`
- Create: `src/main/java/com/vng/wallet/infrastructure/persistence/JpaWalletRepository.java`
- Test:   `src/test/java/com/vng/wallet/infrastructure/persistence/JpaWalletRepositoryTest.java`

- [ ] **Step 1: Viết integration test thất bại (`@DataJpaTest` — chạy với H2 thật)**

```java
package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@Import(JpaWalletRepository.class)   // nạp adapter vào test context
class JpaWalletRepositoryTest {

    @Autowired
    private WalletRepository repository;   // tiêm qua PORT, không phải class cụ thể

    @Test
    void saveThenFind_roundTripsThroughDatabase() {
        Wallet saved = repository.save(Wallet.createNew("Alice"));

        assertNotNull(saved.getId());

        Optional<Wallet> found = repository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("Alice", found.get().getOwnerName());
        assertEquals(0, BigDecimal.ZERO.compareTo(found.get().getBalance()));
    }

    @Test
    void findById_emptyWhenMissing() {
        assertTrue(repository.findById(999L).isEmpty());
    }
}
```

- [ ] **Step 2: Chạy test để xác nhận FAIL**

Run: `mvn -q test -Dtest=JpaWalletRepositoryTest`
Expected: FAIL — các class trong package `persistence` chưa tồn tại.

- [ ] **Step 3: Tạo `WalletEntity` (đối tượng JPA, tách khỏi domain)**

```java
package com.vng.wallet.infrastructure.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "wallet")
public class WalletEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String ownerName;

    private BigDecimal balance;

    protected WalletEntity() {
    }

    public WalletEntity(Long id, String ownerName, BigDecimal balance) {
        this.id = id;
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
}
```

- [ ] **Step 4: Tạo `SpringDataWalletJpa`**

```java
package com.vng.wallet.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data sinh sẵn save/findById cho WalletEntity. */
public interface SpringDataWalletJpa extends JpaRepository<WalletEntity, Long> {
}
```

- [ ] **Step 5: Tạo adapter `JpaWalletRepository` (cài port + map Entity↔domain)**

```java
package com.vng.wallet.infrastructure.persistence;

import com.vng.wallet.domain.Wallet;
import com.vng.wallet.domain.WalletRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * ADAPTER — cài đặt PORT domain bằng JPA. Đây là "cầu nối" map giữa
 * domain Wallet (thuần) và WalletEntity (JPA). Lõi nghiệp vụ không thấy JPA.
 */
@Repository
public class JpaWalletRepository implements WalletRepository {

    private final SpringDataWalletJpa jpa;

    public JpaWalletRepository(SpringDataWalletJpa jpa) {
        this.jpa = jpa;
    }

    @Override
    public Wallet save(Wallet wallet) {
        WalletEntity entity = new WalletEntity(wallet.getId(), wallet.getOwnerName(), wallet.getBalance());
        return toDomain(jpa.save(entity));
    }

    @Override
    public Optional<Wallet> findById(Long id) {
        return jpa.findById(id).map(this::toDomain);
    }

    private Wallet toDomain(WalletEntity e) {
        return new Wallet(e.getId(), e.getOwnerName(), e.getBalance());
    }
}
```

- [ ] **Step 6: Chạy test để xác nhận PASS**

Run: `mvn -q test -Dtest=JpaWalletRepositoryTest`
Expected: PASS (2 tests).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vng/wallet/infrastructure/persistence/ src/test/java/com/vng/wallet/infrastructure/persistence/
git commit -m "refactor: JPA adapter implements WalletRepository port"
```

---

## Task 5: Web layer — di chuyển controller, DTO, exception handler + sửa import

**Files:**
- Create: `src/main/java/com/vng/wallet/infrastructure/web/dto/CreateWalletRequest.java`
- Create: `src/main/java/com/vng/wallet/infrastructure/web/dto/WalletResponse.java`
- Create: `src/main/java/com/vng/wallet/infrastructure/web/WalletController.java`
- Create: `src/main/java/com/vng/wallet/infrastructure/web/GlobalExceptionHandler.java`
- Test:   `src/test/java/com/vng/wallet/infrastructure/web/WalletControllerTest.java`

- [ ] **Step 1: Viết test thất bại (`@SpringBootTest` + MockMvc — test toàn bộ luồng HTTP)**

```java
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
```

- [ ] **Step 2: Chạy test để xác nhận FAIL**

Run: `mvn -q test -Dtest=WalletControllerTest`
Expected: FAIL — controller mới ở package `infrastructure.web` chưa tồn tại (test này import package mới).

- [ ] **Step 3: Tạo DTO `CreateWalletRequest`**

```java
package com.vng.wallet.infrastructure.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateWalletRequest(
        @NotBlank(message = "ownerName must not be empty")
        String ownerName
) {
}
```

- [ ] **Step 4: Tạo DTO `WalletResponse`**

```java
package com.vng.wallet.infrastructure.web.dto;

import com.vng.wallet.domain.Wallet;

import java.math.BigDecimal;

public record WalletResponse(
        Long id,
        String ownerName,
        BigDecimal balance
) {
    public static WalletResponse from(Wallet wallet) {
        return new WalletResponse(wallet.getId(), wallet.getOwnerName(), wallet.getBalance());
    }
}
```

- [ ] **Step 5: Tạo `WalletController`**

```java
package com.vng.wallet.infrastructure.web;

import com.vng.wallet.application.WalletService;
import com.vng.wallet.domain.Wallet;
import com.vng.wallet.infrastructure.web.dto.CreateWalletRequest;
import com.vng.wallet.infrastructure.web.dto.WalletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/wallets")
public class WalletController {

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping
    public ResponseEntity<WalletResponse> createWallet(@Valid @RequestBody CreateWalletRequest request) {
        Wallet wallet = walletService.createWallet(request.ownerName());
        return ResponseEntity.status(HttpStatus.CREATED).body(WalletResponse.from(wallet));
    }

    @GetMapping("/{id}")
    public WalletResponse getWallet(@PathVariable Long id) {
        return WalletResponse.from(walletService.getWallet(id));
    }
}
```

- [ ] **Step 6: Tạo `GlobalExceptionHandler`**

```java
package com.vng.wallet.infrastructure.web;

import com.vng.wallet.domain.WalletNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(WalletNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(WalletNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }
}
```

- [ ] **Step 7: Chạy test** (vẫn có thể FAIL vì còn class trùng tên ở gốc — đó là lý do Task 7)

Run: `mvn -q test -Dtest=WalletControllerTest`
Expected: Có thể FAIL do `@SpringBootTest` quét thấy CẢ controller cũ (gốc) lẫn mới → trùng bean/đường dẫn. Sang Task 7 xoá file cũ rồi chạy lại. Nếu PASS luôn thì càng tốt.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vng/wallet/infrastructure/web/ src/test/java/com/vng/wallet/infrastructure/web/
git commit -m "refactor: move web layer into infrastructure.web"
```

---

## Task 6: Cập nhật `WalletApplication` (đảm bảo quét đúng package)

**Files:**
- Modify: `src/main/java/com/vng/wallet/WalletApplication.java`

- [ ] **Step 1: Xác nhận annotation quét cả cây package**

`@SpringBootApplication` đặt ở `com.vng.wallet` sẽ tự quét mọi sub-package (`domain`, `application`, `infrastructure.*`). KHÔNG cần sửa gì nếu file đã nằm ở `com/vng/wallet/WalletApplication.java`. Mở file xác nhận `package com.vng.wallet;` và không có `scanBasePackages` thu hẹp.

- [ ] **Step 2: Không đổi code → không commit riêng** (chuyển sang Task 7)

---

## Task 7: Xoá cấu trúc phẳng cũ + verify toàn bộ

**Files:**
- Delete: `src/main/java/com/vng/wallet/Wallet.java`
- Delete: `src/main/java/com/vng/wallet/WalletRepository.java`
- Delete: `src/main/java/com/vng/wallet/WalletService.java`
- Delete: `src/main/java/com/vng/wallet/WalletController.java`
- Delete: `src/main/java/com/vng/wallet/WalletNotFoundException.java`
- Delete: `src/main/java/com/vng/wallet/dto/CreateWalletRequest.java`
- Delete: `src/main/java/com/vng/wallet/dto/WalletResponse.java`

- [ ] **Step 1: Xoá các file cũ ở gốc**

```bash
rm src/main/java/com/vng/wallet/Wallet.java \
   src/main/java/com/vng/wallet/WalletRepository.java \
   src/main/java/com/vng/wallet/WalletService.java \
   src/main/java/com/vng/wallet/WalletController.java \
   src/main/java/com/vng/wallet/WalletNotFoundException.java \
   src/main/java/com/vng/wallet/dto/CreateWalletRequest.java \
   src/main/java/com/vng/wallet/dto/WalletResponse.java
rmdir src/main/java/com/vng/wallet/dto 2>/dev/null || true
```

- [ ] **Step 2: Chạy TOÀN BỘ test**

Run: `mvn -q test`
Expected: PASS tất cả (WalletTest, WalletServiceTest, JpaWalletRepositoryTest, WalletControllerTest). Không còn lỗi bean trùng.

- [ ] **Step 3: Khởi động app và kiểm thật bằng curl**

Run (terminal 1): `mvn -q spring-boot:run`
Đợi log `Started WalletApplication`. Sau đó (terminal 2):

```bash
curl -s -X POST http://localhost:8080/wallets -H "Content-Type: application/json" -d '{"ownerName":"Alice"}' -w "\n[%{http_code}]\n"
curl -s http://localhost:8080/wallets/1 -w "\n[%{http_code}]\n"
curl -s http://localhost:8080/wallets/999 -w "\n[%{http_code}]\n"
```
Expected: `201` (balance 0) · `200` · `404`. Dừng app: `pkill -f spring-boot:run`.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "refactor: remove flat structure, complete Clean Architecture layout"
```

---

## Định nghĩa "Done" (Bước 1)

- `domain/` không import Spring/JPA (kiểm: mở `Wallet.java`, `WalletRepository.java` — chỉ có `java.*`).
- 4 file test đều PASS (`mvn test`).
- API `POST /wallets` + `GET /wallets/{id}` hoạt động y như trước (201/200/404).
- Cấu trúc thư mục khớp sơ đồ "Cấu trúc file" ở đầu plan.

## Bước kế tiếp (plan riêng)
- Stage 2: Ledger (`wallet_transaction`) + `topup`/`withdraw` + optimistic locking + idempotency.
- Stage 3: Multi-tenant (TenantContext/Filter + Hibernate schema routing + pre-provision).
- Stage 4: Security HMAC + TraceId.
- Stage 5: Integration test isolation/concurrency/auth.
