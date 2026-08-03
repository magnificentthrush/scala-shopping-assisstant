// Main chat widget — dark theme, shows a centered logo+title when the chat is empty
// (matching the reference UI), otherwise shows the message thread.

import { useState, useRef, useEffect } from "react";
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
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    loadMessages();
  }, [conversationId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  async function loadMessages() {
    try {
      const res = await resumeConversation(conversationId);
      setMessages(res.messages); // empty array is fine — shows the centered welcome state
    } catch (err) {
      console.error("Failed to load conversation:", err);
    }
  }

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
    <div className="flex flex-col h-screen flex-1 bg-[#0e0e0e]">
      {isEmpty ? (
        // Centered welcome state — matches the reference "Chatbot UI" screen
        <div className="flex-1 flex flex-col items-center justify-center text-center px-4">
          <h2 className="text-2xl font-bold text-white mb-1">ShopPilot</h2>
          <p className="text-sm text-gray-500">Tell me what you're looking for — I'll help you find it.</p>
        </div>
      ) : (
        // Normal message thread
        <div className="flex-1 overflow-y-auto px-4 py-6 space-y-4 max-w-2xl w-full mx-auto">
          {messages.map((msg) => (
            <div key={msg.id} className={msg.role === "user" ? "flex justify-end" : "flex justify-start"}>
              <div className="max-w-[80%]">
                <div
                  className={`rounded-2xl px-4 py-2.5 text-sm ${
                    msg.role === "user"
                      ? "bg-gray-700 text-white"
                      : "bg-[#1a1a1a] border border-gray-800 text-gray-200"
                  }`}
                >
                  {msg.content}
                </div>
                {msg.products && msg.products.length > 0 && (
                  <div className="flex gap-3 overflow-x-auto mt-2 pb-2">
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
              <div className="bg-[#1a1a1a] border border-gray-800 rounded-2xl px-4 py-2.5 text-sm text-gray-500">
                Typing...
              </div>
            </div>
          )}
          <div ref={bottomRef} />
        </div>
      )}

      <Input value={input} onChange={setInput} onSend={handleSend} disabled={loading} />
    </div>
  );
}