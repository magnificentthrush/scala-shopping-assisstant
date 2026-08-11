// Chat/messaging API calls — matches docs/API_CONTRACT.md
import { apiFetch } from "./client";
import type { Message, Product } from "../types";
import { registerMockConversation, touchMockConversation, appendMockMessages } from "./conversations";
import { mockProducts } from "../mocks/mockProducts";

// Very simple keyword + budget matcher
function findMatchingProducts(message: string) {
  const lower = message.toLowerCase();
  const budgetMatch = lower.match(/\$?(\d+)/);
  const budget = budgetMatch ? parseInt(budgetMatch[1]) : null;

  const matches = mockProducts.filter((p) => {
    const searchable = `${p.name} ${p.brand} ${p.category}`.toLowerCase();
    const keywordMatch = lower.split(" ").some((word) => word.length > 2 && searchable.includes(word));
    const withinBudget = budget ? p.price <= budget : true;
    return keywordMatch && withinBudget;
  });

  return matches.slice(0, 5);
}
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
   const matchedProducts = findMatchingProducts(message);
    const replyText = matchedProducts.length > 0
      ? `Here are ${matchedProducts.length} options that match what you're looking for.`
      : "I couldn't find a match — try a different keyword or budget.";

    const assistantMessage: Message = {
      id: crypto.randomUUID(),
      role: "assistant",
      content: replyText,
      sequenceNumber: 2,
      createdAt: new Date().toISOString(),
      products: matchedProducts,
    };

    // Save both turns into the conversation's history
    appendMockMessages(conversationId, [userMessage, assistantMessage]);

    return {
      sessionId,
      conversationId,
      mode: "info",
      reply: replyText,
      followUpQuestion: null,
      products: matchedProducts,
      userMessage,
      assistantMessage,
    };
  }
  return apiFetch<SendMessageResponse>(`/api/sessions/${sessionId}/messages`, {
    method: "POST",
    body: JSON.stringify({ message }),
  });
}