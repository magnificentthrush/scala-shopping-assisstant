-- Messages (audit trail), conversation_state (hot-path current filters),
-- and chat_sessions (ephemeral runtime handle onto a durable conversation).
-- Structure only.

-- One row per active connection/tab. Resuming a past conversation creates a
-- NEW chat_sessions row against the SAME conversation_id — history and
-- state in conversations/messages/conversation_state are unaffected.
CREATE TABLE IF NOT EXISTS chat_sessions (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_active_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
  expires_at      TIMESTAMPTZ
);

-- Durable turn-by-turn history and audit trail. filters_snapshot and safe
-- capture what the system believed AT THAT POINT IN TIME — conversation_state
-- (mutable, current-only) cannot answer that after the fact.
-- NOTE: column `safe` is removed by 006_drop_messages_safe.sql (rejected turns
-- are never persisted, so the column added no value).
CREATE TABLE IF NOT EXISTS messages (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  conversation_id  UUID NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
  sequence_number  INT NOT NULL,
  role             TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
  content          TEXT NOT NULL,
  filters_snapshot JSONB,
  safe             BOOLEAN,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE (conversation_id, sequence_number)
);

-- One row per conversation: the CURRENT filters only. Separate from
-- `messages` so the hot-path read ("load filters for this conversation")
-- is a primary-key point lookup, not a scan-and-sort over message history.
CREATE TABLE IF NOT EXISTS conversation_state (
  conversation_id UUID PRIMARY KEY REFERENCES conversations(id) ON DELETE CASCADE,
  filters         JSONB NOT NULL DEFAULT '{}'::jsonb,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
