// Left sidebar — shows conversation history, new chat button, rename/delete

import { useState, useEffect } from "react";
import { Plus, Pencil, Trash2, Check, X } from "lucide-react";
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
  refreshKey: number; // bump this from the parent to force a reload of the list
}

export default function Sidebar({
  activeConversationId,
  onSelectConversation,
  onNewChat,
  refreshKey,
}: SidebarProps) {
  const [conversations, setConversations] = useState<ConversationSummary[]>([]);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editTitle, setEditTitle] = useState("");
  const [deletingId, setDeletingId] = useState<string | null>(null);

  useEffect(() => {
    loadConversations();
  }, [refreshKey]);

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
    // If the deleted chat was open, start a fresh one
    if (id === activeConversationId) {
      onNewChat();
    }
  }

  return (
    <div className="w-64 h-[calc(100vh-64px)] bg-gray-900 text-gray-100 flex flex-col">
      {/* New chat button */}
      <div className="p-3">
        <button
          onClick={onNewChat}
          className="w-full flex items-center gap-2 bg-gray-800 hover:bg-gray-700 text-sm rounded-lg px-3 py-2 transition-colors"
        >
          <Plus size={16} />
          New Chat
        </button>
      </div>

      {/* Conversation list */}
      <div className="flex-1 overflow-y-auto px-2 space-y-1">
        {conversations.length === 0 && (
          <p className="text-xs text-gray-500 px-2 py-4 text-center">No chats yet</p>
        )}

        {conversations.map((convo) => (
          <div
            key={convo.id}
            className={`group flex items-center gap-1 px-2 py-2 rounded-lg cursor-pointer text-sm ${
              convo.id === activeConversationId ? "bg-gray-800" : "hover:bg-gray-800"
            }`}
          >
            {editingId === convo.id ? (
              // Rename mode
              <>
                <input
                  value={editTitle}
                  onChange={(e) => setEditTitle(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && saveEdit(convo.id)}
                  autoFocus
                  className="flex-1 bg-gray-700 text-white text-sm rounded px-2 py-1 outline-none"
                />
                <button onClick={() => saveEdit(convo.id)} className="text-green-400 hover:text-green-300">
                  <Check size={14} />
                </button>
                <button onClick={() => setEditingId(null)} className="text-gray-400 hover:text-gray-300">
                  <X size={14} />
                </button>
              </>
            ) : (
              // Normal display mode
              <>
                <span
                  onClick={() => onSelectConversation(convo.id)}
                  className="flex-1 truncate"
                >
                  {convo.title || "New chat"}
                </span>
                <button
                  onClick={() => startEdit(convo)}
                  className="opacity-0 group-hover:opacity-100 text-gray-400 hover:text-white transition-opacity"
                >
                  <Pencil size={13} />
                </button>
                <button
                  onClick={() => setDeletingId(convo.id)}
                  className="opacity-0 group-hover:opacity-100 text-gray-400 hover:text-red-400 transition-opacity"
                >
                  <Trash2 size={13} />
                </button>
              </>
            )}
          </div>
        ))}
      </div>

      {/* Delete confirmation dialog */}
      {deletingId && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
          <div className="bg-white text-gray-800 rounded-xl p-5 w-80">
            <h3 className="font-semibold mb-2">Delete this chat?</h3>
            <p className="text-sm text-gray-500 mb-4">This action cannot be undone.</p>
            <div className="flex justify-end gap-2">
              <button
                onClick={() => setDeletingId(null)}
                className="px-3 py-1.5 text-sm rounded-lg border border-gray-300 hover:bg-gray-50"
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