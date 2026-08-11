CREATE OR REPLACE FUNCTION commit_assistant_turn(
  p_conversation_id UUID,
  p_content TEXT,
  p_filters JSONB
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
      filters_snapshot
    )
    VALUES (
      p_conversation_id,
      v_seq,
      'assistant',
      p_content,
      p_filters
    )
    RETURNING *;
END;
$$ LANGUAGE plpgsql;
