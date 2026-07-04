-- V1__init.sql
-- Baseline schema matching the current JPA entities (User, Event, Booking).
-- If you already have a live dev database created by Hibernate's ddl-auto,
-- Flyway will baseline it as V1 automatically (spring.flyway.baseline-on-migrate=true)
-- rather than trying to re-run this against existing tables.

CREATE TABLE users (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    email       VARCHAR(255) NOT NULL,
    phone       VARCHAR(255) NOT NULL,
    password    VARCHAR(255) NOT NULL,
    role        VARCHAR(20)  NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE events (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(255)   NOT NULL,
    venue           VARCHAR(255)   NOT NULL,
    event_date      DATETIME(6)    NOT NULL,
    total_seats     INT            NOT NULL,
    available_seats INT            NOT NULL,
    ticket_price    DOUBLE         NOT NULL,
    status          VARCHAR(20)    NOT NULL,
    version         BIGINT         NOT NULL,
    created_at      DATETIME(6)    NOT NULL,
    updated_at      DATETIME(6)    NOT NULL
);

CREATE TABLE bookings (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_ref  VARCHAR(255) NOT NULL,
    user_id      BIGINT       NOT NULL,
    event_id     BIGINT       NOT NULL,
    seats_booked INT          NOT NULL,
    total_amount DOUBLE       NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    booked_at    DATETIME(6)  NOT NULL,
    cancelled_at DATETIME(6)  NULL,
    expires_at   DATETIME(6)  NULL,
    CONSTRAINT uk_bookings_booking_ref UNIQUE (booking_ref),
    CONSTRAINT fk_bookings_user  FOREIGN KEY (user_id)  REFERENCES users (id),
    CONSTRAINT fk_bookings_event FOREIGN KEY (event_id) REFERENCES events (id)
);

CREATE INDEX id_booking_ref ON bookings (booking_ref);
CREATE INDEX id_booking_user_event ON bookings (user_id, event_id);
