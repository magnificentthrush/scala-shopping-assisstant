// Chat input — dark pill-shaped bar with a "+" menu for attaching files/photos

import { useState, useRef, useEffect } from "react";
import { Send, Plus, Paperclip, Image as ImageIcon } from "lucide-react";

interface InputProps {
  value: string;
  onChange: (value: string) => void;
  onSend: () => void;
  disabled?: boolean;
}

export default function Input({ value, onChange, onSend, disabled }: InputProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const [attachedFileName, setAttachedFileName] = useState<string | null>(null);

  const menuRef = useRef<HTMLDivElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const imageInputRef = useRef<HTMLInputElement>(null);

  // Close the menu when clicking outside of it
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
    if (e.key === "Enter" && !disabled) {
      onSend();
    }
  }

  // For now this just remembers the file name — wiring it into the actual
  // message payload happens once the backend supports attachments.
  function handleFileSelected(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (file) {
      setAttachedFileName(file.name);
    }
    setMenuOpen(false);
  }

  return (
    <div className="px-6 pb-6 pt-2">
      <div className="max-w-2xl mx-auto">
        {/* Shows the attached file name above the input, if one was picked */}
        {attachedFileName && (
          <div className="flex items-center justify-between bg-[#242424] border border-gray-700 rounded-lg px-3 py-1.5 mb-2 text-xs text-gray-300">
            <span className="truncate">📎 {attachedFileName}</span>
            <button onClick={() => setAttachedFileName(null)} className="text-gray-500 hover:text-white ml-2">
              ✕
            </button>
          </div>
        )}

        <div className="relative flex items-center gap-2 bg-[#242424] rounded-full pl-2 pr-2 py-2 border border-gray-700">
          {/* "+" attach button */}
          <button
            onClick={() => setMenuOpen((open) => !open)}
            disabled={disabled}
            className="text-gray-500 hover:text-white p-1.5 rounded-full hover:bg-[#333] transition-colors shrink-0 disabled:opacity-50"
          >
            <Plus size={18} />
          </button>

          {/* Dropdown menu */}
          {menuOpen && (
            <div
              ref={menuRef}
              className="absolute bottom-12 left-0 z-20 bg-[#2a2a2a] border border-gray-700 rounded-xl shadow-lg py-1 w-52"
            >
              <button
                onClick={() => fileInputRef.current?.click()}
                className="w-full flex items-center gap-2 px-3 py-2.5 text-sm text-gray-200 hover:bg-[#333] transition-colors"
              >
                <Paperclip size={15} />
                Add files
              </button>
              <button
                onClick={() => imageInputRef.current?.click()}
                className="w-full flex items-center gap-2 px-3 py-2.5 text-sm text-gray-200 hover:bg-[#333] transition-colors"
              >
                <ImageIcon size={15} />
                Add photos
              </button>
            </div>
          )}

          {/* Hidden inputs that the menu buttons trigger */}
          <input
            ref={fileInputRef}
            type="file"
            className="hidden"
            onChange={handleFileSelected}
          />
          <input
            ref={imageInputRef}
            type="file"
            accept="image/*"
            className="hidden"
            onChange={handleFileSelected}
          />

          <input
            type="text"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={disabled}
            placeholder="Send a message..."
            className="flex-1 bg-transparent text-sm text-gray-100 placeholder-gray-500 outline-none disabled:opacity-50"
          />
          <button
            onClick={onSend}
            disabled={disabled}
            className="bg-gray-600 hover:bg-gray-500 text-white rounded-full p-2 disabled:opacity-50 transition-colors shrink-0"
          >
            <Send size={16} />
          </button>
        </div>
      </div>
    </div>
  );
}