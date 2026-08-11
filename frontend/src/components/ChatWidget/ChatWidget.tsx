import { useState, useRef, useEffect } from "react";
import { Copy, ShoppingBag } from "lucide-react";
import type { Message } from "../../types";
import ProductCard from "../ProductCard/ProductCard";
import Input from "./Input/Input";
import { sendMessage } from "../../api/chat";
import { resumeConversation } from "../../api/conversations";
import { getErrorMessage } from "../../api/errors";

interface ChatWidgetProps {
  conversationId: string | null;
  sessionId: string;
  onConversationCreated: (id: string) => void;
  onFirstMessageSent: () => void;
}

export default function ChatWidget({ conversationId, sessionId, onConversationCreated, onFirstMessageSent }: ChatWidgetProps) {
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (conversationId) loadMessages();
    else setMessages([]);
  }, [conversationId]);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  // Auto-dismiss error after 5s
  useEffect(() => {
    if (!error) return;
    const timer = setTimeout(() => setError(null), 5000);
    return () => clearTimeout(timer);
  }, [error]);

  async function loadMessages() {
    if (!conversationId) return;
    try {
      const res = await resumeConversation(conversationId);
      setMessages(res.messages);
    } catch (err) {
      console.error("Failed to load conversation:", err);
    }
  }

  async function handleSend() {
    const trimmed = input.trim();
    if (!trimmed || loading) return;

    const wasFirstMessage = messages.length === 0;

    const optimisticUserMessage: Message = {
      id: "temp-" + Date.now(),
      role: "user",
      content: trimmed,
      sequenceNumber: messages.length + 1,
      createdAt: new Date().toISOString(),
    };
    setMessages((prev) => [...prev, optimisticUserMessage]);
    setInput("");
    setLoading(true);
    setError(null);

    try {
      const res = await sendMessage(sessionId, trimmed);

      // On first message, backend lazy-creates the conversation
      if (!conversationId && res.conversationId) {
        onConversationCreated(res.conversationId);
      }

      // Replace optimistic message with real DB rows
      setMessages((prev) => {
        const withoutOptimistic = prev.filter((m) => m.id !== optimisticUserMessage.id);
        return [
          ...withoutOptimistic,
          { ...res.userMessage },
          {
            ...res.assistantMessage,
            products: res.products,
          },
        ];
      });

      if (wasFirstMessage) onFirstMessageSent();
    } catch (err) {
      // Roll back optimistic message, restore input, show error
      setMessages((prev) => prev.filter((m) => m.id !== optimisticUserMessage.id));
      setInput(trimmed);
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }

  const isEmpty = messages.length === 0;

  return (
    <section className="chat" aria-label="Shopping assistant conversation">
      {error && (
        <div className="chat-error" role="alert">
          <span>{error}</span>
          <button type="button" onClick={() => setError(null)} aria-label="Dismiss error">
            ×
          </button>
        </div>
      )}

      <div className="chat__scroller">
        <div className="chat__thread">
          {isEmpty ? (
            <div className="chat-empty">
              <div className="chat-empty__mark" aria-hidden="true">
                <ShoppingBag size={22} strokeWidth={1.6} />
              </div>
              <h1>What can I help you find?</h1>
              <p>Describe what you need, your budget, or the features that matter most.</p>
            </div>
          ) : (
            <div className="message-list">
              {messages.map((msg) => (
                <article
                  key={msg.id}
                  className={`message message--${msg.role}`}
                  aria-label={msg.role === "user" ? "Your message" : "ShopPilot response"}
                >
                  <div className="message__content">
                    {msg.content ? <div className="message__bubble">{msg.content}</div> : null}

                    {msg.products && msg.products.length > 0 ? (
                      <div className="product-rail" aria-label="Recommended products">
                        {msg.products.map((product) => (
                          <ProductCard key={product.id} product={product} />
                        ))}
                      </div>
                    ) : null}

                    {msg.role === "assistant" ? (
                      <div className="message-actions" aria-label="Message actions">
                        <button
                          type="button"
                          className="icon-button"
                          onClick={() => navigator.clipboard?.writeText(msg.content)}
                          aria-label="Copy response"
                          title="Copy"
                        >
                          <Copy size={15} strokeWidth={1.6} />
                        </button>
                      </div>
                    ) : null}
                  </div>
                </article>
              ))}

              {loading ? (
                <div className="message message--assistant" aria-label="ShopPilot is responding">
                  <div className="typing" aria-hidden="true">
                    <span />
                    <span />
                    <span />
                  </div>
                </div>
              ) : null}
              <div ref={bottomRef} />
            </div>
          )}
        </div>
      </div>

      <Input value={input} onChange={setInput} onSend={handleSend} disabled={loading} />
    </section>
  );
}
