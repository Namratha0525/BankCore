CREATE TABLE users (
    id BIGINT PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('CUSTOMER', 'AUDITOR')),
    token_hash CHAR(64) NOT NULL UNIQUE
) ENGINE=InnoDB;

CREATE TABLE accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('SAVINGS', 'CURRENT')),
    balance BIGINT NOT NULL DEFAULT 0 CHECK (balance >= 0),
    FOREIGN KEY (owner_id) REFERENCES users(id)
) ENGINE=InnoDB;

CREATE TABLE movements (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    kind VARCHAR(16) NOT NULL,
    source_id BIGINT,
    target_id BIGINT,
    amount BIGINT NOT NULL CHECK (amount > 0),
    fee BIGINT NOT NULL CHECK (fee >= 0),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (actor_id) REFERENCES users(id),
    FOREIGN KEY (source_id) REFERENCES accounts(id),
    FOREIGN KEY (target_id) REFERENCES accounts(id)
) ENGINE=InnoDB;

CREATE TABLE requests (
    actor_id BIGINT NOT NULL,
    request_key VARBINARY(128) NOT NULL,
    fingerprint CHAR(64) NOT NULL,
    movement_id BIGINT UNIQUE,
    PRIMARY KEY (actor_id, request_key),
    FOREIGN KEY (actor_id) REFERENCES users(id),
    FOREIGN KEY (movement_id) REFERENCES movements(id)
) ENGINE=InnoDB;

CREATE TABLE audit_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    account_id BIGINT,
    movement_id BIGINT,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    FOREIGN KEY (actor_id) REFERENCES users(id),
    FOREIGN KEY (account_id) REFERENCES accounts(id),
    FOREIGN KEY (movement_id) REFERENCES movements(id)
) ENGINE=InnoDB;

CREATE TRIGGER audit_no_update BEFORE UPDATE ON audit_log FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Audit records are append-only';
CREATE TRIGGER audit_no_delete BEFORE DELETE ON audit_log FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Audit records are append-only';
CREATE TRIGGER movements_no_update BEFORE UPDATE ON movements FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Movement records are append-only';
CREATE TRIGGER movements_no_delete BEFORE DELETE ON movements FOR EACH ROW
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Movement records are append-only';

-- The service account can append ledger records but cannot alter or drop them.
CREATE USER 'bankcore'@'%' IDENTIFIED BY 'bankcore-demo';
GRANT SELECT, INSERT, UPDATE ON bankcore.users TO 'bankcore'@'%';
GRANT SELECT, INSERT, UPDATE ON bankcore.accounts TO 'bankcore'@'%';
GRANT SELECT, INSERT, UPDATE ON bankcore.requests TO 'bankcore'@'%';
GRANT SELECT, INSERT ON bankcore.movements TO 'bankcore'@'%';
GRANT SELECT, INSERT ON bankcore.audit_log TO 'bankcore'@'%';
