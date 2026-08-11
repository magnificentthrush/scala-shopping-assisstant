-- Remove messages.safe: rejected/unsafe turns are never persisted, so every
-- stored user row already passed Call #1. Keeping a always-true column added
-- no query value. Validation still uses Call #1's JSON "safe" field in memory;
-- it is not stored on the messages row.

ALTER TABLE public.messages DROP COLUMN IF EXISTS safe;
