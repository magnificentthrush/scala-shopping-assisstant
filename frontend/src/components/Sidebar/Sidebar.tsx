// Left sidebar — dark blue/black ShopPilot theme, with Settings at the bottom

import { useState, useEffect, useRef } from "react";
import { Plus, Pencil, Trash2, Check, X, Search, MessageSquare, MoreVertical, Settings as SettingsIcon } from "lucide-react";
import type { ConversationSummary } from "../../types";
import {
  listConversations,
  renameConversation,
  deleteConversation,
} from "../../api/conversations";

interface SidebarProps {
  activeConversationId: string | null;
  onSelectConversation: (id: string) => void;
  onNewChat: () => void;
  refreshKey: number;
  onOpenSettings: () => void;
}

export default function Sidebar({
  activeConversationId,
  onSelectConversation,
  onNewChat,
  refreshKey,
  onOpenSettings,
}: SidebarProps) {
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [searchQuery, setSearchQuery] = useState("");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editTitle, setEditTitle] = useState("");
  const [deletingId, setDeletingId] = useState<string | null>(null);
  const [menuOpenId, setMenuOpenId] = useState<string | null>(null);

  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    loadConversations();
  }, [refreshKey]);

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpenId(null);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  async function loadConversations() {
    try {
      const list = await listConversations();
      setConversations(list);
    } catch (err) {
      console.error("Failed to load conversations:", err);
    }
  }

  function startEdit(convo: ConversationSummary) {
    setEditingId(convo.id);
    setEditTitle(convo.title || "New chat");
    setMenuOpenId(null);
  }

  async function saveEdit(id: string) {
    if (editTitle.trim()) {
      await renameConversation(id, editTitle.trim());
      await loadConversations();
    }
    setEditingId(null);
  }

  async function confirmDelete(id: string) {
    await deleteConversation(id);
    setDeletingId(null);
    await loadConversations();
    if (id === activeConversationId) {
      onNewChat();
    }
  }

  const filteredConversations = conversations.filter((c) =>
    (c.title || "New chat").toLowerCase().includes(searchQuery.toLowerCase())
  );

  return (
    <div
      className="w-72 h-screen flex flex-col border-r border-slate-800/60"
      style={{ background: "#05070f" }}
    >
      {/* Brand header */}
      <div className="flex items-center gap-2.5 px-4 pt-4 pb-3">
        <div
          className="flex items-center justify-center w-8 h-8 rounded-lg text-sm font-bold text-white shrink-0"
          style={{
            background: "linear-gradient(135deg, #2b4bff 0%, #0f1b4d 100%)",
            boxShadow: "0 4px 16px rgba(43, 75, 255, 0.4)",
          }}
        >
          S
        </div>
        <span
          className="text-lg font-extrabold tracking-tight"
          style={{
            backgroundImage: "linear-gradient(135deg, #ffffff 0%, #93a8ff 100%)",
            WebkitBackgroundClip: "text",
            WebkitTextFillColor: "transparent",
            backgroundClip: "text",
          }}
        >
          ShopPilot
        </span>
      </div>

      <div className="px-3 pb-2">
        <button
          onClick={onNewChat}
          className="w-full flex items-center justify-center gap-2 bg-white text-[#05070f] hover:bg-slate-200 text-sm font-semibold rounded-xl px-3 py-2.5 transition-colors"
        >
          <Plus size={16} />
          New Chat
        </button>
      </div>

      <div className="px-3 pb-3">
        <div className="relative">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-500" />
          <input
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search chats..."
            className="w-full bg-[#0b1330] border border-slate-800 text-sm text-white placeholder-slate-500 rounded-lg pl-8 pr-3 py-2 outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500/40 transition-colors"
          />
        </div>
      </div>

      <div className="border-t border-slate-800/60 mx-3" />

      <div className="flex-1 overflow-y-auto px-2 py-2 space-y-0.5">
        {filteredConversations.length === 0 && (
          <p className="text-sm text-slate-500 italic text-center py-6">No chats.</p>
        )}

        {filteredConversations.map((convo) => (
          <div
            key={convo.id}
            className={`group relative flex items-center gap-2 px-3 py-2.5 rounded-lg cursor-pointer text-sm transition-colors ${
              convo.id === activeConversationId ? "bg-[#12204a]" : "hover:bg-[#0b1330]"
            }`}
          >
            <MessageSquare size={14} className="text-slate-500 shrink-0" />

            {editingId === convo.id ? (
              <>
                <input
                  value={editTitle}
                  onChange={(e) => setEditTitle(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && saveEdit(convo.id)}
                  autoFocus
                  className="flex-1 bg-[#12204a] text-white text-sm rounded px-2 py-1 outline-none"
                />
                <button onClick={() => saveEdit(convo.id)} className="text-green-400 hover:text-green-300">
                  <Check size={14} />
                </button>
                <button onClick={() => setEditingId(null)} className="text-slate-500 hover:text-white">
                  <X size={14} />
                </button>
              </>
            ) : (
              <>
                <span onClick={() => onSelectConversation(convo.id)} className="flex-1 truncate text-slate-200">
                  {convo.title || "New chat"}
                </span>

                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    setMenuOpenId(menuOpenId === convo.id ? null : convo.id);
                  }}
                  className="opacity-0 group-hover:opacity-100 text-slate-500 hover:text-white transition-opacity shrink-0"
                >
                  <MoreVertical size={15} />
                </button>

                {menuOpenId === convo.id && (
                  <div
                    ref={menuRef}
                    className="absolute right-2 top-10 z-20 bg-[#0b1330] border border-slate-800 rounded-lg shadow-lg py-1 w-32"
                  >
                    <button
                      onClick={() => startEdit(convo)}
                      className="w-full flex items-center gap-2 px-3 py-2 text-sm text-white hover:bg-[#12204a] transition-colors"
                    >
                      <Pencil size={13} />
                      Edit
                    </button>
                    <button
                      onClick={() => {
                        setDeletingId(convo.id);
                        setMenuOpenId(null);
                      }}
                      className="w-full flex items-center gap-2 px-3 py-2 text-sm text-red-400 hover:bg-[#12204a] transition-colors"
                    >
                      <Trash2 size={13} />
                      Delete
                    </button>
                  </div>
                )}
              </>
            )}
          </div>
        ))}
      </div>

      {/* Settings button at the bottom */}
      <div className="border-t border-slate-800/60 p-3">
        <button
          onClick={onOpenSettings}
          className="w-full flex items-center gap-2 px-3 py-2.5 rounded-lg text-sm text-slate-400 hover:bg-[#0b1330] hover:text-white transition-colors"
        >
          <SettingsIcon size={16} />
          Settings
        </button>
      </div>

      {deletingId && (
        <div className="fixed inset-0 bg-black/70 flex items-center justify-center z-50">
          <div className="bg-[#0b1330] text-white rounded-xl p-5 w-80 border border-slate-800">
            <h3 className="font-semibold mb-2">Delete this chat?</h3>
            <p className="text-sm text-slate-400 mb-4">This action cannot be undone.</p>
            <div className="flex justify-end gap-2">
              <button
                onClick={() => setDeletingId(null)}
                className="px-3 py-1.5 text-sm rounded-lg border border-slate-700 text-slate-300 hover:bg-[#12204a]"
              >
                Cancel
              </button>
              <button
                onClick={() => confirmDelete(deletingId)}
                className="px-3 py-1.5 text-sm rounded-lg bg-red-600 text-white hover:bg-red-700"
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}