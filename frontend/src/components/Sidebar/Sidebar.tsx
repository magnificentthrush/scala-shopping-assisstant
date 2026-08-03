// Left sidebar — dark theme, no logo, three-dot menu for edit/delete per chat

import { useState, useEffect, useRef } from "react";
import { Plus, Pencil, Trash2, Check, X, Search, MessageSquare, MoreVertical } from "lucide-react";
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
}

export default function Sidebar({
  activeConversationId,
  onSelectConversation,
  onNewChat,
  refreshKey,
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

  // Close the three-dot menu if the user clicks anywhere outside it
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
    <div className="w-72 h-screen bg-[#171717] text-gray-200 flex flex-col border-r border-gray-800">
      {/* New chat button */}
      <div className="px-3 pt-4 pb-2">
        <button
          onClick={onNewChat}
          className="w-full flex items-center justify-center gap-2 bg-white text-gray-900 hover:bg-gray-100 text-sm font-medium rounded-xl px-3 py-2.5 transition-colors"
        >
          <Plus size={16} />
          New Chat
        </button>
      </div>

      {/* Search box */}
      <div className="px-3 pb-3">
        <div className="relative">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" />
          <input
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search chats..."
            className="w-full bg-[#242424] text-sm text-gray-200 placeholder-gray-500 rounded-lg pl-8 pr-3 py-2 outline-none focus:ring-1 focus:ring-gray-600"
          />
        </div>
      </div>

      <div className="border-t border-gray-800 mx-3" />

      {/* Conversation list */}
      <div className="flex-1 overflow-y-auto px-2 py-2 space-y-0.5">
        {filteredConversations.length === 0 && (
          <p className="text-sm text-gray-500 italic text-center py-6">No chats.</p>
        )}

        {filteredConversations.map((convo) => (
          <div
            key={convo.id}
            className={`group relative flex items-center gap-2 px-3 py-2.5 rounded-lg cursor-pointer text-sm transition-colors ${
              convo.id === activeConversationId ? "bg-[#2a2a2a]" : "hover:bg-[#212121]"
            }`}
          >
            <MessageSquare size={14} className="text-gray-500 shrink-0" />

            {editingId === convo.id ? (
              <>
                <input
                  value={editTitle}
                  onChange={(e) => setEditTitle(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && saveEdit(convo.id)}
                  autoFocus
                  className="flex-1 bg-[#333] text-white text-sm rounded px-2 py-1 outline-none"
                />
                <button onClick={() => saveEdit(convo.id)} className="text-green-400 hover:text-green-300">
                  <Check size={14} />
                </button>
                <button onClick={() => setEditingId(null)} className="text-gray-400 hover:text-gray-300">
                  <X size={14} />
                </button>
              </>
            ) : (
              <>
                <span onClick={() => onSelectConversation(convo.id)} className="flex-1 truncate">
                  {convo.title || "New chat"}
                </span>

                {/* Three-dot menu trigger */}
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    setMenuOpenId(menuOpenId === convo.id ? null : convo.id);
                  }}
                  className="opacity-0 group-hover:opacity-100 text-gray-500 hover:text-white transition-opacity shrink-0"
                >
                  <MoreVertical size={15} />
                </button>

                {/* Dropdown menu with Edit / Delete */}
                {menuOpenId === convo.id && (
                  <div
                    ref={menuRef}
                    className="absolute right-2 top-10 z-20 bg-[#2a2a2a] border border-gray-700 rounded-lg shadow-lg py-1 w-32"
                  >
                    <button
                      onClick={() => startEdit(convo)}
                      className="w-full flex items-center gap-2 px-3 py-2 text-sm text-gray-200 hover:bg-[#333] transition-colors"
                    >
                      <Pencil size={13} />
                      Edit
                    </button>
                    <button
                      onClick={() => {
                        setDeletingId(convo.id);
                        setMenuOpenId(null);
                      }}
                      className="w-full flex items-center gap-2 px-3 py-2 text-sm text-red-400 hover:bg-[#333] transition-colors"
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

      {/* Delete confirmation dialog */}
      {deletingId && (
        <div className="fixed inset-0 bg-black/60 flex items-center justify-center z-50">
          <div className="bg-[#242424] text-gray-100 rounded-xl p-5 w-80 border border-gray-700">
            <h3 className="font-semibold mb-2">Delete this chat?</h3>
            <p className="text-sm text-gray-400 mb-4">This action cannot be undone.</p>
            <div className="flex justify-end gap-2">
              <button
                onClick={() => setDeletingId(null)}
                className="px-3 py-1.5 text-sm rounded-lg border border-gray-600 hover:bg-gray-700"
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