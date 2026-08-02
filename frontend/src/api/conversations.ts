// Conversation list, resume, rename, delete — matches docs/API_CONTRACT.md
import { apiFetch } from "./client";
import type { ConversationSummary, Message } from "../types";

const USE_MOCK_API = true;

// In-memory mock stores — persist for the browser session
let mockConversations: ConversationSummary[] = [];
const mockMessages: Record<string, Message[]> = {};

interface ResumeResponse {
  conversationId: string;
  sessionId: string;
  title: string | null;
  messages: Message[];
}

export async function listConversations(): Promise<ConversationSummary[]> {
  if (USE_MOCK_API) {
    return [...mockConversations].sort(
      (a, b) => new Date(b.lastMessageAt).getTime() - new Date(a.lastMessageAt).getTime()
    );
  }
  const res = await apiFetch<{ conversations: ConversationSummary[] }>("/api/conversations");
  return res.conversations;
}

// Called when a brand-new conversation is created
export function registerMockConversation(id: string) {
  const now = new Date().toISOString();
  mockConversations.push({ id, title: null, createdAt: now, updatedAt: now, lastMessageAt: now });
  mockMessages[id] = [];
}

// Called after every message exchange to bump order + set title from the first message
export function touchMockConversation(id: string, firstMessage?: string) {
  const convo = mockConversations.find((c) => c.id === id);
  if (convo) {
    convo.lastMessageAt = new Date().toISOString();
    if (!convo.title && firstMessage) {
      convo.title = firstMessage.slice(0, 40);
    }
  }
}

// Appends the user + assistant turn to that conversation's saved history
export function appendMockMessages(id: string, newMessages: Message[]) {
  if (!mockMessages[id]) mockMessages[id] = [];
  mockMessages[id].push(...newMessages);
}

export async function resumeConversation(conversationId: string): Promise<ResumeResponse> {
  if (USE_MOCK_API) {
    return {
      conversationId,
      sessionId: crypto.randomUUID(),
      title: mockConversations.find((c) => c.id === conversationId)?.title ?? null,
      messages: mockMessages[conversationId] || [],
    };
  }
  return apiFetch<ResumeResponse>(`/api/conversations/${conversationId}/resume`, { method: "POST" });
}

export async function renameConversation(conversationId: string, title: string): Promise<void> {
  if (USE_MOCK_API) {
    const convo = mockConversations.find((c) => c.id === conversationId);
    if (convo) convo.title = title;
    return;
  }
  await apiFetch(`/api/conversations/${conversationId}`, {
    method: "PATCH",
    body: JSON.stringify({ title }),
  });
}

export async function deleteConversation(conversationId: string): Promise<void> {
  if (USE_MOCK_API) {
    mockConversations = mockConversations.filter((c) => c.id !== conversationId);
    delete mockMessages[conversationId];
    return;
  }
  await apiFetch(`/api/conversations/${conversationId}`, { method: "DELETE" });
}