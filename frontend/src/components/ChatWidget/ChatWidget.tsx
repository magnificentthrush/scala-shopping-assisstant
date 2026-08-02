// Main chat widget — receives conversationId/sessionId from the parent (Chat.tsx)
// so switching conversations in the sidebar reloads the right thread

import { useState, useRef, useEffect } from "react";
import type { Message } from "../../types";
import ProductCard from "../ProductCard/ProductCard";
import Input from "./Input/Input";
import { sendMessage } from "../../api/chat";
import { resumeConversation } from "../../api/conversations";

interface ChatWidgetProps {
  conversationId: string;
  sessionId: string;
  onFirstMessageSent: () => void; // tells the sidebar to refresh (title/order changed)
}

export default function ChatWidget({ conversationId, sessionId, onFirstMessageSent }: ChatWidgetProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const bottomRef = useRef<HTMLDivElement>(null);

  // Whenever the conversation changes (sidebar click, new chat), reload its messages
  useEffect(() => {
    loadMessages();
  }, [conversationId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  async function loadMessages() {
    try {
      const res = await resumeConversation(conversationId);
      if (res.messages.length > 0) {
        setMessages(res.messages);
      } else {
        setMessages([
          {
            id: "welcome",
            role: "assistant",
            content: "Hi! Tell me what you're looking for — I'll help you find it.",
            sequenceNumber: 0,
            createdAt: new Date().toISOString(),
          },
        ]);
      }
    } catch (err) {
      console.error("Failed to load conversation:", err);
    }
  }

  async function handleSend() {
    if (!input.trim() || loading) return;

    const wasFirstMessage = messages.length <= 1;

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
      if (wasFirstMessage) onFirstMessageSent(); // refresh sidebar so title/order updates
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

  return (
    <div className="flex flex-col h-[calc(100vh-64px)] flex-1 bg-gray-50">
      <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4 max-w-2xl w-full mx-auto">
        {messages.map((msg) => (
          <div key={msg.id} className={msg.role === "user" ? "flex justify-end" : "flex justify-start"}>
            <div className="max-w-[80%]">
              <div
                className={`rounded-2xl px-4 py-2 text-sm ${
                  msg.role === "user"
                    ? "bg-blue-600 text-white"
                    : "bg-white border border-gray-200 text-gray-800"
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
            <div className="bg-white border border-gray-200 rounded-2xl px-4 py-2 text-sm text-gray-400">
              Typing...
            </div>
          </div>
        )}
        <div ref={bottomRef} />
      </div>

      <div className="max-w-2xl w-full mx-auto">
        <Input value={input} onChange={setInput} onSend={handleSend} disabled={loading} />
      </div>
    </div>
  );
}