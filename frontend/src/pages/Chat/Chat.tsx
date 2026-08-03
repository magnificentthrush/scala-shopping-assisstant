// Chat page — combines the sidebar with the active chat widget
// On load: resumes the most recent conversation if one exists, otherwise starts a new one

import { useState, useEffect, useRef } from "react";
import { LoaderCircle, Menu } from "lucide-react";
import Sidebar from "../../components/Sidebar/Sidebar";
import ChatWidget from "../../components/ChatWidget/ChatWidget";
import { startConversation } from "../../api/chat";
import { listConversations, resumeConversation } from "../../api/conversations";

export default function Chat() {
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [sidebarRefreshKey, setSidebarRefreshKey] = useState(0);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [initializationError, setInitializationError] = useState("");

  // Guards against React StrictMode running this effect twice in dev
  const hasInitialized = useRef(false);

  useEffect(() => {
    if (hasInitialized.current) return;
    hasInitialized.current = true;
    initChat();
  }, []);

  // On first load: reuse the most recent chat if one exists, don't spam new ones
  async function initChat() {
    setInitializationError("");
    try {
      const existing = await listConversations();
      if (existing.length > 0) {
        await handleSelectConversation(existing[0].id);
      } else {
        await handleNewChat();
      }
    } catch {
      setInitializationError("We couldn’t load your chats. Check your connection and try again.");
    }
  }

  async function handleNewChat() {
    const res = await startConversation();
    setConversationId(res.conversationId);
    setSessionId(res.sessionId);
    setSidebarRefreshKey((k) => k + 1);
  }

  async function handleSelectConversation(id: string) {
    const res = await resumeConversation(id);
    setConversationId(res.conversationId);
    setSessionId(res.sessionId);
  }

  if (!conversationId || !sessionId) {
    return (
      <div className="flex min-h-dvh items-center justify-center bg-[#0e0e0e] px-6 text-center">
        {initializationError ? (
          <div role="alert" className="max-w-sm">
            <h1 className="text-pretty text-xl font-semibold text-white">Couldn’t Open ShopPilot</h1>
            <p className="mt-2 text-sm text-gray-400">{initializationError}</p>
            <button
              type="button"
              onClick={initChat}
              className="mt-5 rounded-xl bg-white px-4 py-2.5 text-sm font-semibold text-gray-950 hover:bg-gray-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400"
            >
              Try Again
            </button>
          </div>
        ) : (
          <div role="status" aria-live="polite" className="flex items-center gap-2 text-sm text-gray-400">
            <LoaderCircle aria-hidden="true" size={18} className="animate-spin" />
            Loading ShopPilot…
          </div>
        )}
      </div>
    );
  }

  return (
     <div className="relative flex h-dvh overflow-hidden bg-[#0e0e0e]">
      <button
        type="button"
        aria-label="Open conversation sidebar"
        aria-expanded={sidebarOpen}
        onClick={() => setSidebarOpen(true)}
        className="fixed left-3 top-3 z-20 rounded-full border border-white/10 bg-[#171717]/90 p-2.5 text-gray-300 shadow-lg backdrop-blur hover:bg-[#242424] hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400 md:hidden"
      >
        <Menu aria-hidden="true" size={18} />
      </button>
      <Sidebar
        activeConversationId={conversationId}
        onSelectConversation={handleSelectConversation}
        onNewChat={handleNewChat}
        refreshKey={sidebarRefreshKey}
        isOpen={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
      />
      <ChatWidget
        conversationId={conversationId}
        sessionId={sessionId}
        onFirstMessageSent={() => setSidebarRefreshKey((k) => k + 1)}
      />
    </div>
  );
}