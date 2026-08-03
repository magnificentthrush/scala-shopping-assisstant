// Left sidebar — dark theme, no logo, three-dot menu for edit/delete per chat

import { useState, useEffect, useRef } from "react";
import { Plus, Pencil, Trash2, Check, X, Search, MessageSquare, MoreVertical, PanelLeftClose } from "lucide-react";
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
  isOpen: boolean;
  onClose: () => void;
}

export default function Sidebar({
  activeConversationId,
  onSelectConversation,
  onNewChat,
  refreshKey,
  isOpen,
  onClose,
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

  useEffect(() => {
    if (!deletingId) return;

    function handleEscape(e: KeyboardEvent) {
      if (e.key === "Escape") setDeletingId(null);
    }

    document.addEventListener("keydown", handleEscape);
    return () => document.removeEventListener("keydown", handleEscape);
  }, [deletingId]);

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
    <>
      {isOpen && (
        <button
          type="button"
          aria-label="Close conversation sidebar"
          className="fixed inset-0 z-30 bg-black/60 backdrop-blur-[1px] md:hidden"
          onClick={onClose}
        />
      )}
      <aside
        aria-label="Conversation history"
        className={`fixed inset-y-0 left-0 z-40 h-dvh w-[min(18rem,88vw)] flex-col border-r border-gray-800 bg-[#171717] text-gray-200 shadow-2xl md:static md:z-auto md:flex md:w-72 md:translate-x-0 md:shadow-none ${
          isOpen ? "flex translate-x-0" : "hidden -translate-x-full"
        }`}
      >
      {/* New chat button */}
      <div className="flex items-center gap-2 px-3 pb-2 pt-4">
        <button
          type="button"
          onClick={() => {
            onNewChat();
            onClose();
          }}
          className="flex w-full items-center justify-center gap-2 rounded-xl bg-white px-3 py-2.5 text-sm font-medium text-gray-900 transition-colors hover:bg-gray-100 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400"
        >
          <Plus aria-hidden="true" size={16} />
          New Chat
        </button>
        <button
          type="button"
          aria-label="Close conversation sidebar"
          onClick={onClose}
          className="rounded-lg p-2.5 text-gray-400 hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400 md:hidden"
        >
          <PanelLeftClose aria-hidden="true" size={18} />
        </button>
      </div>

      {/* Search box */}
      <div className="px-3 pb-3">
        <div className="relative">
          <Search aria-hidden="true" size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-gray-500" />
          <label htmlFor="conversation-search" className="sr-only">Search Chats</label>
          <input
            id="conversation-search"
            name="conversation-search"
            type="search"
            autoComplete="off"
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search chats…"
            className="w-full rounded-lg bg-[#242424] py-2 pl-8 pr-3 text-sm text-gray-200 placeholder-gray-500 hover:bg-[#292929] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400"
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
            <MessageSquare aria-hidden="true" size={14} className="text-gray-500 shrink-0" />

            {editingId === convo.id ? (
              <>
                <input
                  aria-label="Conversation title"
                  name="conversation-title"
                  autoComplete="off"
                  value={editTitle}
                  onChange={(e) => setEditTitle(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") saveEdit(convo.id);
                    if (e.key === "Escape") setEditingId(null);
                  }}
                  autoFocus
                  className="min-w-0 flex-1 rounded bg-[#333] px-2 py-1 text-sm text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400"
                />
                <button type="button" aria-label="Save conversation title" onClick={() => saveEdit(convo.id)} className="rounded p-1 text-green-400 hover:bg-white/10 hover:text-green-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400">
                  <Check aria-hidden="true" size={14} />
                </button>
                <button type="button" aria-label="Cancel editing" onClick={() => setEditingId(null)} className="rounded p-1 text-gray-400 hover:bg-white/10 hover:text-gray-300 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400">
                  <X aria-hidden="true" size={14} />
                </button>
              </>
            ) : (
              <>
                <button
                  type="button"
                  onClick={() => {
                    onSelectConversation(convo.id);
                    onClose();
                  }}
                  className="min-w-0 flex-1 truncate rounded text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400"
                >
                  {convo.title || "New chat"}
                </button>

                {/* Three-dot menu trigger */}
                <button
                  type="button"
                  aria-label={`Open options for ${convo.title || "New chat"}`}
                  aria-expanded={menuOpenId === convo.id}
                  onClick={(e) => {
                    e.stopPropagation();
                    setMenuOpenId(menuOpenId === convo.id ? null : convo.id);
                  }}
                  className="shrink-0 rounded p-1 text-gray-500 opacity-100 transition-opacity hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400 md:opacity-0 md:group-hover:opacity-100 md:focus-visible:opacity-100"
                >
                  <MoreVertical aria-hidden="true" size={15} />
                </button>

                {/* Dropdown menu with Edit / Delete */}
                {menuOpenId === convo.id && (
                  <div
                    ref={menuRef}
                    role="menu"
                    className="absolute right-2 top-10 z-20 bg-[#2a2a2a] border border-gray-700 rounded-lg shadow-lg py-1 w-32"
                  >
                    <button
                      type="button"
                      role="menuitem"
                      onClick={() => startEdit(convo)}
                      className="w-full flex items-center gap-2 px-3 py-2 text-sm text-gray-200 hover:bg-[#333] transition-colors"
                    >
                      <Pencil aria-hidden="true" size={13} />
                      Rename
                    </button>
                    <button
                      type="button"
                      role="menuitem"
                      onClick={() => {
                        setDeletingId(convo.id);
                        setMenuOpenId(null);
                      }}
                      className="w-full flex items-center gap-2 px-3 py-2 text-sm text-red-400 hover:bg-[#333] transition-colors"
                    >
                      <Trash2 aria-hidden="true" size={13} />
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
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4 overscroll-contain">
          <div role="alertdialog" aria-modal="true" aria-labelledby="delete-title" aria-describedby="delete-description" className="w-full max-w-sm rounded-xl border border-gray-700 bg-[#242424] p-5 text-gray-100 shadow-2xl">
            <h2 id="delete-title" className="mb-2 font-semibold">Delete This Chat?</h2>
            <p id="delete-description" className="mb-4 text-sm text-gray-400">This action cannot be undone.</p>
            <div className="flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setDeletingId(null)}
                className="rounded-lg border border-gray-600 px-3 py-1.5 text-sm hover:bg-gray-700 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={() => confirmDelete(deletingId)}
                className="rounded-lg bg-red-600 px-3 py-1.5 text-sm text-white hover:bg-red-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-red-400"
              >
                Delete
              </button>
            </div>
          </div>
        </div>
      )}
      </aside>
    </>
  );
}