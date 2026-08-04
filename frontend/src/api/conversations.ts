// Conversation list, resume, rename, delete — matches docs/API_CONTRACT.md
// Mock data is persisted to localStorage so it survives page reloads.

import { apiFetch } from "./client";
import type { ConversationSummary, Message } from "../types";

const USE_MOCK_API = true;

const CONVOS_KEY = "mock_conversations";
const MESSAGES_KEY = "mock_messages";

// --- localStorage helpers ---
function loadConversations(): ConversationSummary[] {
  const raw = localStorage.getItem(CONVOS_KEY);
  return raw ? JSON.parse(raw) : [];
}

function saveConversations(list: ConversationSummary[]) {
  localStorage.setItem(CONVOS_KEY, JSON.stringify(list));
}

function loadMessagesStore(): Record<string, Message[]> {
  const raw = localStorage.getItem(MESSAGES_KEY);
  return raw ? JSON.parse(raw) : {};
}

function saveMessagesStore(store: Record<string, Message[]>) {
  localStorage.setItem(MESSAGES_KEY, JSON.stringify(store));
}

interface ResumeResponse {
  conversationId: string;
  sessionId: string;
  title: string | null;
  messages: Message[];
}

export async function listConversations(): Promise<ConversationSummary[]> {
  if (USE_MOCK_API) {
    return loadConversations().sort(
      (a, b) => new Date(b.lastMessageAt).getTime() - new Date(a.lastMessageAt).getTime()
    );
  }
  const res = await apiFetch<{ conversations: ConversationSummary[] }>("/api/conversations");
  return res.conversations;
}

export function registerMockConversation(id: string) {
  const now = new Date().toISOString();
  const list = loadConversations();
  list.push({ id, title: null, createdAt: now, updatedAt: now, lastMessageAt: now });
  saveConversations(list);

  const messagesStore = loadMessagesStore();
  messagesStore[id] = [];
  saveMessagesStore(messagesStore);
}

export function touchMockConversation(id: string, firstMessage?: string) {
  const list = loadConversations();
  const convo = list.find((c) => c.id === id);
  if (convo) {
    convo.lastMessageAt = new Date().toISOString();
    if (!convo.title && firstMessage) {
      convo.title = firstMessage.slice(0, 40);
    }
    saveConversations(list);
  }
}

export function appendMockMessages(id: string, newMessages: Message[]) {
  const messagesStore = loadMessagesStore();
  if (!messagesStore[id]) messagesStore[id] = [];
  messagesStore[id].push(...newMessages);
  saveMessagesStore(messagesStore);
}

export async function resumeConversation(conversationId: string): Promise<ResumeResponse> {
  if (USE_MOCK_API) {
    const list = loadConversations();
    const messagesStore = loadMessagesStore();
    return {
      conversationId,
      sessionId: crypto.randomUUID(),
      title: list.find((c) => c.id === conversationId)?.title ?? null,
      messages: messagesStore[conversationId] || [],
    };
  }
  return apiFetch<ResumeResponse>(`/api/conversations/${conversationId}/resume`, { method: "POST" });
}

export async function renameConversation(conversationId: string, title: string): Promise<void> {
  if (USE_MOCK_API) {
    const list = loadConversations();
    const convo = list.find((c) => c.id === conversationId);
    if (convo) {
      convo.title = title;
      saveConversations(list);
    }
    return;
  }
  await apiFetch(`/api/conversations/${conversationId}`, {
    method: "PATCH",
    body: JSON.stringify({ title }),
  });
}

export async function deleteConversation(conversationId: string): Promise<void> {
  if (USE_MOCK_API) {
    const list = loadConversations().filter((c) => c.id !== conversationId);
    saveConversations(list);

    const messagesStore = loadMessagesStore();
    delete messagesStore[conversationId];
    saveMessagesStore(messagesStore);
    return;
  }
  await apiFetch(`/api/conversations/${conversationId}`, { method: "DELETE" });
}