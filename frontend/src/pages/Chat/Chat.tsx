// Chat page — combines the sidebar with the active chat widget
// On load: resumes the most recent conversation if one exists, otherwise starts a new one

import { useState, useEffect, useRef } from "react";
import Sidebar from "../../components/Sidebar/Sidebar";
import ChatWidget from "../../components/ChatWidget/ChatWidget";
import { startConversation } from "../../api/chat";
import { listConversations, resumeConversation } from "../../api/conversations";

export default function Chat() {
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [sidebarRefreshKey, setSidebarRefreshKey] = useState(0);

  // Guards against React StrictMode running this effect twice in dev
  const hasInitialized = useRef(false);

  useEffect(() => {
    if (hasInitialized.current) return;
    hasInitialized.current = true;
    initChat();
  }, []);

  // On first load: reuse the most recent chat if one exists, don't spam new ones
  async function initChat() {
    const existing = await listConversations();
    if (existing.length > 0) {
      await handleSelectConversation(existing[0].id);
    } else {
      await handleNewChat();
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
    return <div className="p-6 text-gray-500">Loading...</div>;
  }

  return (
     <div className="flex relative">
      <Sidebar
        activeConversationId={conversationId}
        onSelectConversation={handleSelectConversation}
        onNewChat={handleNewChat}
        refreshKey={sidebarRefreshKey}
      />
      <ChatWidget
        conversationId={conversationId}
        sessionId={sessionId}
        onFirstMessageSent={() => setSidebarRefreshKey((k) => k + 1)}
      />
    </div>
  );
}