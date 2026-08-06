import { useState, useEffect, useRef } from "react";
import {
  Check,
  MoreHorizontal,
  PanelLeftClose,
  Pencil,
  Search,
  Settings,
  SquarePen,
  Trash2,
  X,
} from "lucide-react";
import type { ConversationSummary } from "../../types";
import BrandLogo from "../BrandLogo/BrandLogo";
import { useAuth } from "../../context/AuthContext";
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
  isOpen: boolean;
  onClose: () => void;
}

export default function Sidebar({
  activeConversationId,
  onSelectConversation,
  onNewChat,
  refreshKey,
  onOpenSettings,
  isOpen,
  onClose,
}: SidebarProps) {
  const { user } = useAuth();
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

  const initials = user?.fullName
    ? user.fullName
        .trim()
        .split(" ")
        .map((part) => part[0])
        .slice(0, 2)
        .join("")
        .toUpperCase()
    : "SP";

  return (
    <aside className={`sidebar ${isOpen ? "sidebar--open" : ""}`} aria-label="Conversation sidebar">
      <div className="sidebar__header">
        <div className="sidebar__brand">
          <BrandLogo compact />
        </div>
        <button type="button" className="icon-button sidebar__close" onClick={onClose} aria-label="Close sidebar">
          <PanelLeftClose size={19} strokeWidth={1.7} />
        </button>
      </div>

      <div className="sidebar__actions">
        <button type="button" onClick={onNewChat} className="sidebar__new-chat">
          <span className="sidebar__new-chat-label">
            <SquarePen size={18} strokeWidth={1.7} />
            New chat
          </span>
        </button>
      </div>

      <div className="sidebar__search-wrap">
        <div className="sidebar__search">
          <Search size={15} strokeWidth={1.7} aria-hidden="true" />
          <input
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            placeholder="Search chats"
            aria-label="Search conversations"
          />
        </div>
      </div>

      <div className="sidebar__section-label">Chats</div>

      <div className="sidebar__list">
        {filteredConversations.length === 0 && (
          <p className="sidebar__empty">No conversations found</p>
        )}

        {filteredConversations.map((convo) => (
          <div
            key={convo.id}
            className={`conversation-row ${
              convo.id === activeConversationId ? "conversation-row--active" : ""
            } ${menuOpenId === convo.id ? "conversation-row--menu" : ""}`}
          >
            {editingId === convo.id ? (
              <div className="conversation-row__edit">
                <input
                  value={editTitle}
                  onChange={(e) => setEditTitle(e.target.value)}
                  onKeyDown={(e) => e.key === "Enter" && saveEdit(convo.id)}
                  autoFocus
                  aria-label="Conversation title"
                />
                <button
                  type="button"
                  onClick={() => saveEdit(convo.id)}
                  className="icon-button"
                  aria-label="Save title"
                >
                  <Check size={15} strokeWidth={1.8} />
                </button>
                <button
                  type="button"
                  onClick={() => setEditingId(null)}
                  className="icon-button"
                  aria-label="Cancel editing"
                >
                  <X size={15} strokeWidth={1.8} />
                </button>
              </div>
            ) : (
              <>
                <button
                  type="button"
                  onClick={() => onSelectConversation(convo.id)}
                  className="conversation-row__select"
                >
                  {convo.title || "New chat"}
                </button>

                <button
                  type="button"
                  onClick={(e) => {
                    e.stopPropagation();
                    setMenuOpenId(menuOpenId === convo.id ? null : convo.id);
                  }}
                  className="icon-button conversation-row__menu-trigger"
                  aria-label={`Actions for ${convo.title || "New chat"}`}
                  aria-expanded={menuOpenId === convo.id}
                >
                  <MoreHorizontal size={17} strokeWidth={1.8} />
                </button>

                {menuOpenId === convo.id && (
                  <div ref={menuRef} className="popover-menu">
                    <button type="button" onClick={() => startEdit(convo)}>
                      <Pencil size={15} strokeWidth={1.7} />
                      Rename
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        setDeletingId(convo.id);
                        setMenuOpenId(null);
                      }}
                      className="popover-menu__danger"
                    >
                      <Trash2 size={15} strokeWidth={1.7} />
                      Delete
                    </button>
                  </div>
                )}
              </>
            )}
          </div>
        ))}
      </div>

      <div className="sidebar__footer">
        <button type="button" onClick={onOpenSettings} className="sidebar__settings">
          <span className="mini-avatar" aria-hidden="true">{initials}</span>
          <span className="sidebar__settings-copy">
            <span>{user?.fullName || "ShopPilot user"}</span>
            <small>{user?.email || "Open settings"}</small>
          </span>
          <Settings size={16} strokeWidth={1.7} aria-hidden="true" />
        </button>
      </div>

      {deletingId && (
        <div className="dialog-backdrop" role="presentation">
          <div className="dialog" role="dialog" aria-modal="true" aria-labelledby="delete-dialog-title">
            <div className="dialog__header">
              <h3 id="delete-dialog-title">Delete this chat?</h3>
            </div>
            <div className="dialog__body">
              <p className="dialog__description">This action cannot be undone.</p>
              <div className="dialog__actions">
                <button type="button" onClick={() => setDeletingId(null)} className="button">
                Cancel
              </button>
              <button
                type="button"
                onClick={() => confirmDelete(deletingId)}
                className="button button--primary button--danger"
              >
                Delete
              </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </aside>
  );
}