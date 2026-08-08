import { useState } from "react";
import { ArrowUp, File } from "lucide-react";
import figmaCloseIcon from "../../../assets/figma-icons/header-edit.svg";

interface InputProps {
  value: string;
  onChange: (value: string) => void;
  onSend: (file: File | null) => void;
  disabled?: boolean;
}

export default function Input({
  value,
  onChange,
  onSend,
  disabled,
}: InputProps) {
  const [attachedFile, setAttachedFile] = useState<File | null>(null);

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Enter" && !disabled) {
      handleSendClick();
    }
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
              <img
                src={figmaCloseIcon}
                alt=""
                className="figma-icon"
              />
            </button>
          </div>
        )}

        <div className="composer__box">
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

        <p className="composer__fine-print">
          ShopPilot can make mistakes. Check important product details.
        </p>
      </div>
    </div>
  );
}