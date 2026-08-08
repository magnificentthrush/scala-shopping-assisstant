import { useState, useEffect, useRef } from "react";
import { Menu } from "lucide-react";
import Sidebar from "../../components/Sidebar/Sidebar";
import ChatWidget from "../../components/ChatWidget/ChatWidget";
import ProfileAvatar from "../../components/Navbar/Navbar";
import Settings from "../../components/Settings/Settings";
import { startConversation } from "../../api/chat";
import { listConversations, resumeConversation } from "../../api/conversations";

export default function Chat() {
  const [conversationId, setConversationId] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [sidebarRefreshKey, setSidebarRefreshKey] = useState(0);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const hasInitialized = useRef(false);

  useEffect(() => {
    if (hasInitialized.current) return;
    hasInitialized.current = true;
    initChat();
  }, []);

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
    setSidebarOpen(false);
  }

  async function handleSelectConversation(id: string) {
    const res = await resumeConversation(id);
    setConversationId(res.conversationId);
    setSessionId(res.sessionId);
    setSidebarOpen(false);
  }

  if (!conversationId || !sessionId) {
    return (
      <div className="app-loading" role="status" aria-label="Loading your conversations">
        <div className="app-loading__content">
          <div className="app-loading__dot" />
        </div>
      </div>
    );
  }

  return (
    <div className="app-shell">
      <Sidebar
        activeConversationId={conversationId}
        onSelectConversation={handleSelectConversation}
        onNewChat={handleNewChat}
        refreshKey={sidebarRefreshKey}
        onOpenSettings={() => setSettingsOpen(true)}
        isOpen={sidebarOpen}
        onClose={() => setSidebarOpen(false)}
      />
      <button
        type="button"
        className={`sidebar-backdrop ${sidebarOpen ? "sidebar-backdrop--open" : ""}`}
        onClick={() => setSidebarOpen(false)}
        aria-label="Close conversation sidebar"
      />

      <main className="app-main">
        <header className="app-topbar">
          <div className="app-topbar__left">
            <button
              type="button"
              className="icon-button mobile-menu-button"
              onClick={() => setSidebarOpen(true)}
              aria-label="Open conversation sidebar"
            >
              <Menu size={20} strokeWidth={1.8} />
            </button>
          </div>
          <ProfileAvatar onOpenSettings={() => setSettingsOpen(true)} />
        </header>

        <ChatWidget
          conversationId={conversationId}
          sessionId={sessionId}
          onFirstMessageSent={() => setSidebarRefreshKey((k) => k + 1)}
        />
      </main>
      {settingsOpen && <Settings onClose={() => setSettingsOpen(false)} />}
    </div>
  );
}