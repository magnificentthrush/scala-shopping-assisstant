import { useState, useRef, useEffect } from "react";
import type { Message } from "../../types";
import ProductCard from "../ProductCard/ProductCard";
import Input from "./Input/Input";
import { sendMessage } from "../../api/chat";
import { resumeConversation } from "../../api/conversations";

interface ChatMessage extends Message {
  attachedImageUrl?: string;
}

interface ChatWidgetProps {
  conversationId: string;
  sessionId: string;
  onFirstMessageSent: () => void;
}

export default function ChatWidget({ conversationId, sessionId, onFirstMessageSent }: ChatWidgetProps) {
  const [messages, setMessages] = useState<ChatMessage[]>([]);
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
      setMessages(res.messages);
    } catch (err) {
      console.error("Failed to load conversation:", err);
    }
  }

  async function handleSend(file: File | null) {
    if ((!input.trim() && !file) || loading) return;

    const wasFirstMessage = messages.length === 0;
    const attachedImageUrl =
      file && file.type.startsWith("image/") ? URL.createObjectURL(file) : undefined;

    const optimisticUserMessage: ChatMessage = {
      id: "temp-" + Date.now(),
      role: "user",
      content: input || (file ? `📎 ${file.name}` : ""),
      sequenceNumber: messages.length + 1,
      createdAt: new Date().toISOString(),
      attachedImageUrl,
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
    <div className="flex flex-col h-screen flex-1 bg-[var(--bg-app)]">
      {isEmpty ? (
        <div className="flex-1 flex flex-col items-center justify-center text-center px-4">
          <div
            className="flex items-center justify-center w-12 h-12 rounded-xl text-xl font-bold text-white mb-3"
            style={{
              background: "linear-gradient(135deg, #2b4bff 0%, #0f1b4d 100%)",
              boxShadow: "0 4px 20px rgba(43, 75, 255, 0.45)",
            }}
          >
            S
          </div>
          <h2 className="text-2xl font-bold text-[var(--text-primary)] mb-1">ShopPilot</h2>
          <p className="text-sm text-[var(--text-secondary)]">Tell me what you're looking for — I'll help you find it.</p>
        </div>
      ) : (
        <div className="flex-1 overflow-y-auto px-4 py-6 space-y-4 max-w-2xl w-full mx-auto">
          {messages.map((msg) => (
            <div key={msg.id} className={msg.role === "user" ? "flex justify-end" : "flex justify-start"}>
              <div className="max-w-[80%]">
                {msg.attachedImageUrl && (
                  <img src={msg.attachedImageUrl} alt="Attached" className="max-w-[220px] rounded-xl mb-1.5 ml-auto" />
                )}

                {msg.content && (
                  <div
                    className={`rounded-2xl px-4 py-2.5 text-sm ${
                      msg.role === "user"
                        ? "bg-[var(--bubble-user-bg)] text-[var(--bubble-user-text)]"
                        : "bg-[var(--bg-surface)] border border-[var(--border-color)] text-[var(--text-primary)]"
                    }`}
                  >
                    {msg.content}
                  </div>
                )}

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
              <div className="bg-[var(--bg-surface)] border border-[var(--border-color)] rounded-2xl px-4 py-2.5 text-sm text-[var(--text-secondary)]">
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