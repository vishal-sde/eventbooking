-- V102__organizer_id_nullable.sql
-- The events.organizer_id column (from an earlier "event organizer and approval"
-- feature) is NOT NULL with no default on databases that have it, but the current
-- Event entity doesn't set it. Relax the constraint so event creation works.
--
-- This is written defensively: some environments (a fresh database, e.g. a new
-- Docker/Railway deploy) never had that column added in the first place, since
-- it came from a migration outside this project's history. Only run the ALTER
-- if the column actually exists, so this migration is a safe no-op everywhere else.

SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'events'
      AND column_name = 'organizer_id'
);

SET @sql := IF(@column_exists > 0,
    'ALTER TABLE events MODIFY COLUMN organizer_id BIGINT NULL',
    'SELECT 1'
);

PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;