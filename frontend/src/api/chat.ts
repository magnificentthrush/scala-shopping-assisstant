// Chat/messaging API calls — matches docs/API_CONTRACT.md
import { apiFetch } from "./client";
import type { Message, Product } from "../types";
import { registerMockConversation, touchMockConversation, appendMockMessages } from "./conversations";
import { mockSearchProducts } from "../mocks/mockSearch";

const USE_MOCK_API = false;

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
  return apiFetch<StartConversationResponse>("/api/conversations", {
    method: "POST",
  });
}

export async function sendMessage(
  sessionId: string,
  conversationId: string,
  message: string
): Promise<SendMessageResponse> {
  if (USE_MOCK_API) {
    await new Promise((r) => setTimeout(r, 900)); // simulate network/LLM latency
    touchMockConversation(conversationId, message);

    const { products, budget } = mockSearchProducts(message);

    let replyText: string;
    let mode: SendMessageResponse["mode"];

    if (products.length > 0) {
      mode = "recommend";
      const budgetPart = budget ? ` under $${budget}` : "";
      replyText = `Here are ${products.length} option${products.length > 1 ? "s" : ""}${budgetPart} that match what you're looking for.`;
    } else {
      mode = "clarify";
      replyText = "I couldn't find an exact match — could you tell me more about what you're looking for (brand, category, or budget)?";
    }

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
      content: replyText,
      sequenceNumber: 2,
      createdAt: new Date().toISOString(),
      products,
    };

    appendMockMessages(conversationId, [userMessage, assistantMessage]);

    return {
      sessionId,
      conversationId,
      mode,
      reply: replyText,
      followUpQuestion: null,
      products,
      userMessage,
      assistantMessage,
    };
  }

  return apiFetch<SendMessageResponse>(`/api/sessions/${sessionId}/messages`, {
    method: "POST",
    body: JSON.stringify({ message }),
  });
}