-- Supporting indexes for the conversation/auth tables added in 002 and 003.
CREATE INDEX IF NOT EXISTS conversations_user_id_idx
  ON conversations (user_id);

-- Powers "GET /api/conversations" ordered by last_message_at DESC.
CREATE INDEX IF NOT EXISTS conversations_last_message_at_idx
  ON conversations (last_message_at DESC);

CREATE INDEX IF NOT EXISTS messages_conversation_id_idx
  ON messages (conversation_id);

CREATE INDEX IF NOT EXISTS chat_sessions_conversation_id_idx
  ON chat_sessions (conversation_id);

CREATE INDEX IF NOT EXISTS chat_sessions_user_id_idx
  ON chat_sessions (user_id);
