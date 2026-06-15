-- Wallet table (= SP4 target structure: balance + held escrow + optimistic version).
-- Portable across H2 and MySQL: BIGINT identity, NUMERIC(38,2) money, no engine-specific syntax.
CREATE TABLE wallet (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    VARCHAR(255)   NOT NULL,
    owner_name VARCHAR(255),
    balance    NUMERIC(38, 2) DEFAULT 0,
    held       NUMERIC(38, 2) NOT NULL DEFAULT 0,
    version    BIGINT
);
