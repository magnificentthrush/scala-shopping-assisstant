-- Lazy-create support (see docs/ARCHITECTURE.md §4 "Why lazy-create
-- conversations"): a chat_sessions row is created the moment a user starts
-- a new chat, before any message exists. conversation_id only gets set once
-- the first message passes Call #1 and a conversations row is lazily
-- created. 003_add_messages_and_state.sql wrongly made this column
-- NOT NULL, which would make lazy-create impossible — this is the
-- corrective fix (docs/ARCHITECTURE.md §2, forward-only migration policy;
-- never hand-edit 003 after it has been applied).
ALTER TABLE chat_sessions
  ALTER COLUMN conversation_id DROP NOT NULL;
