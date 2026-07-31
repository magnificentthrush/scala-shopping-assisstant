// Main chat widget that manages messages, products, and user input
// This component communicates with the backend through api/client.ts

import { useState, useRef, useEffect } from "react";
import type { ConversationTurn, Product } from "../../types";
import ProductCard from "../ProductCard/ProductCard";
import Input from "./Input/Input";
import { startConversation, sendMessage } from "../../api/client";

// An assistant message can also include product recommendations
interface ChatMessage extends ConversationTurn {
  products?: Product[];
}

export default function ChatWidget() {
  // Stores the complete list of chat messages.
  // Updating this state automatically refreshes the UI.
  const [messages, setMessages] = useState<ChatMessage[]>([]);

  // Current text entered by the user
  const [input, setInput] = useState("");

  // Conversation ID returned by the backend.
  // Every message is sent using this ID.
  const [conversationId, setConversationId] = useState<string | null>(null);

  // True while waiting for a response from the backend
  const [loading, setLoading] = useState(false);

  // Reference used to automatically scroll to the latest message
  const bottomRef = useRef<HTMLDivElement>(null);

  // Start a new conversation when the component is mounted
  useEffect(() => {
    initConversation();
  }, []); // Empty dependency array means this runs only once

  // Automatically scroll to the bottom whenever a new message is added
  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages]);

  // Start a new conversation with the backend
  async function initConversation() {
    try {
      const res = await startConversation();
      setConversationId(res.conversationId);

      // Display the welcome message if the backend provides one
      if (res.reply?.reply) {
        setMessages([{ role: "assistant", content: res.reply.reply }]);
      }
    } catch (err) {
      console.error("Failed to start conversation:", err);

      // Display a fallback message if the backend is unavailable
      setMessages([
        {
          role: "assistant",
          content: "Hi! Tell me what you're looking for — I'll help you find it.",
        },
      ]);
    }
  }

  // Called when the user clicks the Send button or presses Enter
  async function handleSend() {
    // Ignore empty messages or prevent sending while a request is in progress
    if (!input.trim() || loading) return;

    // Show the user's message immediately without waiting for the backend
    const userMessage: ChatMessage = { role: "user", content: input };
    setMessages((prev) => [...prev, userMessage]);
    setInput("");      // Clear the input field
    setLoading(true);  // Show the "Typing..." indicator

    try {
      if (!conversationId) throw new Error("Conversation has not been started.");

      // Send the user's message to the backend and receive a response
      const reply = await sendMessage(conversationId, userMessage.content);

      // Add the assistant's reply to the chat
      setMessages((prev) => [
        ...prev,
        { role: "assistant", content: reply.reply, products: reply.products },
      ]);
    } catch (err) {
      console.error("Failed to send message:", err);

      setMessages((prev) => [
        ...prev,
        {
          role: "assistant",
          content: "Sorry, something went wrong. Please try again.",
        },
      ]);
    } finally {
      setLoading(false); // Stop the loading state whether successful or not
    }
  }

  return (
    <div className="flex flex-col h-[calc(100vh-64px)] max-w-2xl mx-auto bg-gray-50">
      {/* Display all chat messages here. This section is scrollable. */}
      <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4">
        {messages.map((msg, idx) => (
          <div
            key={idx}
            // Align user messages to the right and assistant messages to the left
            className={msg.role === "user" ? "flex justify-end" : "flex justify-start"}
          >
            <div className="max-w-[80%]">
              {/* Chat message bubble */}
              <div
                className={`rounded-2xl px-4 py-2 text-sm ${
                  msg.role === "user"
                    ? "bg-blue-600 text-white" // Blue bubble for user messages
                    : "bg-white border border-gray-200 text-gray-800" // White bubble for assistant messages
                }`}
              >
                {msg.content}
              </div>

              {/* Display product cards if the assistant returned products */}
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

        {/* Show a typing indicator while waiting for the backend response */}
        {loading && (
          <div className="flex justify-start">
            <div className="bg-white border border-gray-200 rounded-2xl px-4 py-2 text-sm text-gray-400">
              Typing...
            </div>
          </div>
        )}

        {/* Empty element used as the scroll target */}
        <div ref={bottomRef} />
      </div>

      {/* Fixed input field at the bottom of the chat */}
      <Input
        value={input}
        onChange={setInput}
        onSend={handleSend}
        disabled={loading}
      />
    </div>
  );
}