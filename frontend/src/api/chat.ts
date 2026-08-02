// Chat/messaging API calls — matches docs/API_CONTRACT.md
import { apiFetch } from "./client";
import type { Message, Product } from "../types";
import { registerMockConversation, touchMockConversation, appendMockMessages } from "./conversations";

const USE_MOCK_API = true;

interface StartConversationResponse {
  conversationId: string;
  sessionId: string;
  title: string | null;
  messages: Message[];
}

interface SendMessageResponse {
  sessionId: string;
  conversationId: string;
  mode: "recommend" | "clarify" | "info" | "other";
  reply: string;
  followUpQuestion: string | null;
  products: Product[];
  userMessage: Message;
  assistantMessage: Message;
}

export async function startConversation(): Promise<StartConversationResponse> {
  if (USE_MOCK_API) {
    const conversationId = crypto.randomUUID();
    registerMockConversation(conversationId);
    return {
      conversationId,
      sessionId: crypto.randomUUID(),
      title: null,
      messages: [],
    };
  }
  return apiFetch<StartConversationResponse>("/api/conversations", { method: "POST" });
}

export async function sendMessage(
  sessionId: string,
  conversationId: string,
  message: string
): Promise<SendMessageResponse> {
  if (USE_MOCK_API) {
    await new Promise((r) => setTimeout(r, 800));
    touchMockConversation(conversationId, message);

    const userMessage: Message = {
      id: crypto.randomUUID(),
      role: "user",
      content: message,
      sequenceNumber: 1,
      createdAt: new Date().toISOString(),
    };
    const assistantMessage: Message = {
      id: crypto.randomUUID(),
      role: "assistant",
      content: "This is a mock reply — the real chat backend isn't wired up yet.",
      sequenceNumber: 2,
      createdAt: new Date().toISOString(),
      products: [],
    };

    // Save both turns into the conversation's history
    appendMockMessages(conversationId, [userMessage, assistantMessage]);

    return {
      sessionId,
      conversationId,
      mode: "info",
      reply: assistantMessage.content,
      followUpQuestion: null,
      products: [],
      userMessage,
      assistantMessage,
    };
  }
  return apiFetch<SendMessageResponse>(`/api/sessions/${sessionId}/messages`, {
    method: "POST",
    body: JSON.stringify({ message }),
  });
}