-- V104__add_email_verification.sql
-- Adds OTP-based email verification for new self-registrations.
--
-- DEFAULT TRUE is deliberate: every user who already exists (including the
-- admin account and anyone who registered before this feature shipped) is
-- backfilled as verified automatically, so nobody gets locked out of an
-- account they already had. Only NEW registrations going forward are
-- created with emailVerified = false by application code (UserService),
-- which then requires an OTP before they can log in.

ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT TRUE;