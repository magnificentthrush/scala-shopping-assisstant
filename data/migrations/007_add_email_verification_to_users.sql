-- Adds email verification support to users. Structure only, per the
-- forward-only migration policy in docs/ARCHITECTURE.md §2.
-- See docs/authPlan.md for the auth pipeline that uses these columns.
ALTER TABLE users
  ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT false,
  ADD COLUMN IF NOT EXISTS verification_token_hash TEXT,
  ADD COLUMN IF NOT EXISTS verification_token_expires_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_users_verification_token_hash
  ON users(verification_token_hash);
