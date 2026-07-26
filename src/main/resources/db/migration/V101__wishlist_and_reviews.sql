-- V3__wishlist_and_reviews.sql
-- Adds wishlist (save-for-later) and post-event review/rating support.

CREATE TABLE wishlist_items (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    event_id    BIGINT       NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uk_wishlist_user_event UNIQUE (user_id, event_id),
    CONSTRAINT fk_wishlist_user  FOREIGN KEY (user_id)  REFERENCES users (id),
    CONSTRAINT fk_wishlist_event FOREIGN KEY (event_id) REFERENCES events (id)
);

CREATE TABLE reviews (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT       NOT NULL,
    event_id    BIGINT       NOT NULL,
    rating      INT          NOT NULL,
    comment     VARCHAR(1000) NULL,
    created_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uk_review_user_event UNIQUE (user_id, event_id),
    CONSTRAINT fk_review_user  FOREIGN KEY (user_id)  REFERENCES users (id),
    CONSTRAINT fk_review_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT chk_review_rating CHECK (rating BETWEEN 1 AND 5)
);

CREATE INDEX idx_wishlist_user ON wishlist_items (user_id);
CREATE INDEX idx_review_event ON reviews (event_id);