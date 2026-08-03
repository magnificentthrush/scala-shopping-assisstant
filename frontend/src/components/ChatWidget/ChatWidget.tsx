// Main chat widget — dark theme, shows a centered logo+title when the chat is empty
// (matching the reference UI), otherwise shows the message thread.

import { useState, useRef, useEffect, useCallback } from "react";
import type { Message } from "../../types";
import ProductCard from "../ProductCard/ProductCard";
import Input from "./Input/Input";
import { sendMessage } from "../../api/chat";
import { resumeConversation } from "../../api/conversations";

interface ChatWidgetProps {
  conversationId: string;
  sessionId: string;
  onFirstMessageSent: () => void;
}

export default function ChatWidget({ conversationId, sessionId, onFirstMessageSent }: ChatWidgetProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState("");
  const bottomRef = useRef<HTMLDivElement>(null);

  const loadMessages = useCallback(async () => {
    setLoadError("");
    try {
      const res = await resumeConversation(conversationId);
      setMessages(res.messages);
    } catch {
      setLoadError("We couldn’t load this conversation. Try again.");
    }
  }, [conversationId]);

  useEffect(() => {
    loadMessages();
  }, [loadMessages]);

  useEffect(() => {
    const reduceMotion = window.matchMedia("(prefers-reduced-motion: reduce)").matches;
    bottomRef.current?.scrollIntoView({ behavior: reduceMotion ? "auto" : "smooth" });
  }, [messages]);

  async function handleSend() {
    if (!input.trim() || loading) return;

    const wasFirstMessage = messages.length === 0;

    const optimisticUserMessage: Message = {
      id: "temp-" + Date.now(),
      role: "user",
      content: input,
      sequenceNumber: messages.length + 1,
      createdAt: new Date().toISOString(),
    };
    setMessages((prev) => [...prev, optimisticUserMessage]);
    setInput("");
    setLoading(true);

    try {
      const res = await sendMessage(sessionId, conversationId, optimisticUserMessage.content);
      setMessages((prev) => [
        ...prev,
        {
          id: res.assistantMessage.id,
          role: "assistant",
          content: res.reply,
          sequenceNumber: res.assistantMessage.sequenceNumber,
          createdAt: res.assistantMessage.createdAt,
          products: res.products,
        },
      ]);
      if (wasFirstMessage) onFirstMessageSent();
    } catch (err) {
      console.error("Failed to send message:", err);
      setMessages((prev) => [
        ...prev,
        {
          id: "error-" + Date.now(),
          role: "assistant",
          content: "Sorry, something went wrong. Please try again.",
          sequenceNumber: messages.length + 2,
          createdAt: new Date().toISOString(),
        },
      ]);
    } finally {
      setLoading(false);
    }
  }

  const isEmpty = messages.length === 0;

  return (
    <section aria-label="Shopping assistant chat" className="flex h-dvh min-w-0 flex-1 flex-col bg-[#0e0e0e]">
      {loadError && (
        <div role="alert" className="mx-auto mt-20 flex w-[calc(100%-2rem)] max-w-2xl items-center justify-between gap-3 rounded-xl border border-red-900/60 bg-red-950/30 px-4 py-3 text-sm text-red-200">
          <span>{loadError}</span>
          <button type="button" onClick={loadMessages} className="shrink-0 rounded-lg px-2 py-1 font-semibold hover:bg-red-900/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-400">
            Try Again
          </button>
        </div>
      )}
      {isEmpty ? (
        // Centered welcome state — matches the reference "Chatbot UI" screen
        <div className="flex flex-1 flex-col items-center justify-center px-5 text-center">
          <div className="mb-5 flex h-12 w-12 items-center justify-center rounded-2xl bg-blue-600 text-xl font-bold text-white shadow-lg shadow-blue-950/40" aria-hidden="true">
            S
          </div>
          <h1 className="text-balance text-3xl font-bold tracking-tight text-white">ShopPilot</h1>
          <p className="mt-2 max-w-md text-pretty text-sm leading-6 text-gray-400">
            Describe what you need, your budget, and any must-have features. ShopPilot will help you compare the best options.
          </p>
        </div>
      ) : (
        // Normal message thread
        <div role="log" aria-live="polite" aria-relevant="additions" className="mx-auto w-full max-w-3xl flex-1 space-y-5 overflow-y-auto px-4 pb-6 pt-20 sm:px-6">
          {messages.map((msg) => (
            <div key={msg.id} className={msg.role === "user" ? "flex justify-end" : "flex justify-start"}>
              <div className="min-w-0 max-w-[88%] sm:max-w-[80%]">
                <div
                  className={`whitespace-pre-wrap wrap-break-word rounded-2xl px-4 py-3 text-sm leading-6 ${
                    msg.role === "user"
                      ? "bg-blue-600 text-white"
                      : "bg-[#1a1a1a] border border-gray-800 text-gray-200"
                  }`}
                >
                  {msg.content}
                </div>
                {msg.products && msg.products.length > 0 && (
                  <div aria-label="Recommended products" className="mt-3 flex gap-3 overflow-x-auto pb-2">
                    {msg.products.map((p) => (
                      <ProductCard key={p.id} product={p} />
                    ))}
                  </div>
                )}
              </div>
            </div>
          ))}

          {loading && (
            <div className="flex justify-start">
              <div role="status" className="rounded-2xl border border-gray-800 bg-[#1a1a1a] px-4 py-2.5 text-sm text-gray-400">
                Finding options…
              </div>
            </div>
          )}
          <div ref={bottomRef} />
        </div>
      )}

      <Input value={input} onChange={setInput} onSend={handleSend} disabled={loading} />
    </section>
  );
}