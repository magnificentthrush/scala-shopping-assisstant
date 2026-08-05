import { useState, useRef, useEffect } from "react";
import { Copy, RefreshCcw, ShoppingBag, ThumbsDown, ThumbsUp, Volume2 } from "lucide-react";
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
    <section className="chat" aria-label="Shopping assistant conversation">
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
                    {msg.attachedImageUrl ? (
                      <img src={msg.attachedImageUrl} alt="Attached preview" className="message__attachment" />
                    ) : null}

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
                        <button type="button" className="icon-button" aria-label="Good response" title="Good response">
                          <ThumbsUp size={15} strokeWidth={1.6} />
                        </button>
                        <button type="button" className="icon-button" aria-label="Bad response" title="Bad response">
                          <ThumbsDown size={15} strokeWidth={1.6} />
                        </button>
                        <button type="button" className="icon-button" aria-label="Read aloud" title="Read aloud">
                          <Volume2 size={15} strokeWidth={1.6} />
                        </button>
                        <button type="button" className="icon-button" aria-label="Regenerate response" title="Regenerate">
                          <RefreshCcw size={15} strokeWidth={1.6} />
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