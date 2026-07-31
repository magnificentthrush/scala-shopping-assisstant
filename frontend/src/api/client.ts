// Ye file Scala backend ke saath saari communication handle karti hai
// Baaki poori app isi file ke functions call karegi, direct axios kahin aur use nahi hoga

import axios from "axios";
import type { AssistantReply } from "../types";

// Backend ka URL - abhi local hai, deploy hone ke baad ye .env se change hoga
const BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

// Axios ka instance banaya taake har request mein baar baar URL na likhna pare
const api = axios.create({
  baseURL: BASE_URL,
  headers: { "Content-Type": "application/json" },
});

// Naya conversation shuru karne ke liye (jab user pehli dafa chat kholta hai)
export async function startConversation() {
  const res = await api.post("/api/conversations");
  return res.data; // { conversationId, reply }
}

// User ka message backend ko bhejna aur reply lena
export async function sendMessage(
  conversationId: string,
  message: string
): Promise<AssistantReply> {
  const res = await api.post(`/api/conversations/${conversationId}/messages`, {
    content: message,
  });
  return res.data;
}

export default api;