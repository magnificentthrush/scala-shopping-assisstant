import { useState, useRef, useEffect } from "react";
import { ArrowUp, File, Image as ImageIcon, Paperclip, Plus } from "lucide-react";
import figmaCloseIcon from "../../../assets/figma-icons/header-edit.svg";

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

  const canSend = Boolean(value.trim() || attachedFile);

  return (
    <div className="composer-shell">
      <div className="composer">
        {attachedFile && (
          <div className="attachment-chip">
            <File size={15} strokeWidth={1.7} aria-hidden="true" />
            <span>{attachedFile.name}</span>
            <button
              type="button"
              onClick={() => setAttachedFile(null)}
              className="icon-button"
              aria-label="Remove attachment"
            >
              <img src={figmaCloseIcon} alt="" className="figma-icon" />
            </button>
          </div>
        )}

        <div className="composer__box">
          <button
            type="button"
            onClick={() => setMenuOpen((open) => !open)}
            disabled={disabled}
            className="icon-button"
            aria-label="Add an attachment"
            aria-expanded={menuOpen}
          >
            <Plus size={19} strokeWidth={1.7} />
          </button>

          {menuOpen && (
            <div ref={menuRef} className="composer__menu">
              <button type="button" onClick={() => fileInputRef.current?.click()}>
                <Paperclip size={17} strokeWidth={1.7} />
                Add files
              </button>
              <button type="button" onClick={() => imageInputRef.current?.click()}>
                <ImageIcon size={17} strokeWidth={1.7} />
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
            placeholder="Ask ShopPilot"
            className="composer__input"
            aria-label="Message ShopPilot"
          />
          <button
            type="button"
            onClick={handleSendClick}
            disabled={disabled || !canSend}
            className="composer__send"
            aria-label="Send message"
          >
            <ArrowUp size={18} strokeWidth={2} />
          </button>
        </div>
        <p className="composer__fine-print">ShopPilot can make mistakes. Check important product details.</p>
      </div>
    </div>
  );
}