-- V103__ensure_image_url.sql
-- events.image_url has existed on some databases since before this project's
-- own migration history began (added by a legacy, untracked migration), but
-- our V1/V100/V101/V102 set never actually creates it — so a genuinely fresh
-- database (new Docker container, new Railway deploy, etc.) never gets it.
-- Add it defensively: only if it doesn't already exist.

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'events'
      AND column_name = 'image_url'
);

SET @sql := IF(@column_exists = 0,
    'ALTER TABLE events ADD COLUMN image_url VARCHAR(500) NULL',
    'SELECT 1'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;