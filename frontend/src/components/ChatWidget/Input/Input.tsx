import { useState, useRef, useEffect } from "react";
import { Send, Plus, Paperclip, Image as ImageIcon } from "lucide-react";

interface InputProps {
  value: string;
  onChange: (value: string) => void;
  onSend: (file: File | null) => void;
  disabled?: boolean;
}

export default function Input({ value, onChange, onSend, disabled }: InputProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [attachedFile, setAttachedFile] = useState<File | null>(null);

  const menuRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    function handleClickOutside(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    }
    document.addEventListener("mousedown", handleClickOutside);
    return () => document.removeEventListener("mousedown", handleClickOutside);
  }, []);

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Enter" && !disabled) handleSendClick();
  }

  function handleFileSelected(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (file) setAttachedFile(file);
    setMenuOpen(false);
    e.target.value = "";
  }

  function handleSendClick() {
    onSend(attachedFile);
    setAttachedFile(null);
  }

  return (
    <div className="px-6 pb-6 pt-2">
      <div className="max-w-2xl mx-auto">
        {attachedFile && (
          <div className="flex items-center justify-between bg-[var(--bg-surface)] border border-[var(--border-color)] rounded-lg px-3 py-1.5 mb-2 text-xs text-[var(--text-primary)]">
            <span className="truncate">📎 {attachedFile.name}</span>
            <button onClick={() => setAttachedFile(null)} className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] ml-2">
              ✕
            </button>
          </div>
        )}

        <div className="relative flex items-center gap-2 bg-[var(--bg-surface)] rounded-full pl-2 pr-2 py-2 border border-[var(--border-color)]">
          <button
            onClick={() => setMenuOpen((open) => !open)}
            disabled={disabled}
            className="text-[var(--text-secondary)] hover:text-[var(--text-primary)] p-1.5 rounded-full hover:bg-[var(--bg-surface-alt)] transition-colors shrink-0 disabled:opacity-50"
          >
            <Plus size={18} />
          </button>

          {menuOpen && (
            <div
              ref={menuRef}
              className="absolute bottom-12 left-0 z-20 bg-[var(--bg-surface-alt)] border border-[var(--border-color)] rounded-xl shadow-lg py-1 w-52"
            >
              <button
                onClick={() => fileInputRef.current?.click()}
                className="w-full flex items-center gap-2 px-3 py-2.5 text-sm text-[var(--text-primary)] hover:bg-[var(--bg-surface)] transition-colors"
              >
                <Paperclip size={15} />
                Add files
              </button>
              <button
                onClick={() => imageInputRef.current?.click()}
                className="w-full flex items-center gap-2 px-3 py-2.5 text-sm text-[var(--text-primary)] hover:bg-[var(--bg-surface)] transition-colors"
              >
                <ImageIcon size={15} />
                Add photos
              </button>
            </div>
          )}

          <input ref={fileInputRef} type="file" className="hidden" onChange={handleFileSelected} />
          <input ref={imageInputRef} type="file" accept="image/*" className="hidden" onChange={handleFileSelected} />

          <input
            type="text"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={disabled}
            placeholder="Send a message e.g. waterproof running shoes under $100...""
            className="flex-1 bg-transparent text-sm text-[var(--text-primary)] placeholder-[var(--text-secondary)] outline-none disabled:opacity-50"
          />
          <button
            onClick={handleSendClick}
            disabled={disabled}
            className="bg-[var(--btn-bg)] text-[var(--btn-text)] hover:bg-[var(--btn-bg-hover)] rounded-full p-2 disabled:opacity-50 transition-colors shrink-0"
          >
            <Send size={16} />
          </button>
        </div>
      </div>
    </div>
  );
}