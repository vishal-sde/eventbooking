-- V100__event_details.sql
-- Adds fields needed for the event details page and category + location filtering.
-- NOTE: image_url already exists on this database (added by an earlier migration),
-- so it is intentionally NOT touched here.

ALTER TABLE events
    ADD COLUMN description TEXT NULL,
    ADD COLUMN category    VARCHAR(30) NOT NULL DEFAULT 'OTHER',
    ADD COLUMN city        VARCHAR(120) NULL;

CREATE INDEX idx_events_category ON events (category);
CREATE INDEX idx_events_city ON events (city);