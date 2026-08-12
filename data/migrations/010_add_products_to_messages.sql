-- Persist the recommended products as a JSONB snapshot on each assistant
-- message so product cards survive conversation reload (resume).
-- Nullable: user rows and legacy assistant rows stay NULL.

ALTER TABLE messages ADD COLUMN products JSONB;

-- Drop + recreate (not CREATE OR REPLACE) because adding a column to
-- `messages` changes the `SETOF messages` composite return type, which
-- CREATE OR REPLACE forbids.
DROP FUNCTION commit_assistant_turn(UUID, TEXT, JSONB);

CREATE FUNCTION commit_assistant_turn(
  p_conversation_id UUID,
  p_content TEXT,
  p_filters JSONB,
  p_products JSONB
) RETURNS SETOF messages AS $$
DECLARE
  v_seq INT;
BEGIN
  -- Serialize phase-B writes per conversation to avoid lost filter updates.
  PERFORM 1
  FROM conversation_state
  WHERE conversation_id = p_conversation_id
  FOR UPDATE;

  SELECT COALESCE(MAX(sequence_number), 0) + 1
  INTO v_seq
  FROM messages
  WHERE conversation_id = p_conversation_id;

  UPDATE conversation_state
  SET
    filters = p_filters,
    updated_at = now()
  WHERE conversation_id = p_conversation_id;

  UPDATE conversations
  SET last_message_at = now()
  WHERE id = p_conversation_id;

  RETURN QUERY
    INSERT INTO messages (
      conversation_id,
      sequence_number,
      role,
      content,
      filters_snapshot,
      products
    )
    VALUES (
      p_conversation_id,
      v_seq,
      'assistant',
      p_content,
      p_filters,
      p_products
    )
    RETURNING *;
END;
$$ LANGUAGE plpgsql;
