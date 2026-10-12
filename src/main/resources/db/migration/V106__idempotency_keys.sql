CREATE TABLE idempotency_keys (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id       BIGINT        NOT NULL,
    endpoint      VARCHAR(100)  NOT NULL,
    idem_key      VARCHAR(100)  NOT NULL,
    request_hash  VARCHAR(64)   NOT NULL,
    state         VARCHAR(20)   NOT NULL,
    response_code INT           NULL,
    response_body VARCHAR(4000) NULL,
    created_at    DATETIME(6)   NOT NULL,
    expires_at    DATETIME(6)   NOT NULL,
    CONSTRAINT uk_idempotency UNIQUE (user_id, endpoint, idem_key),
    CONSTRAINT fk_idempotency_user FOREIGN KEY (user_id) REFERENCES users (id)
);
CREATE INDEX idx_idempotency_expires ON idempotency_keys (expires_at);