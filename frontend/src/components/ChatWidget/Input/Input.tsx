import { ArrowUp } from "lucide-react";

interface InputProps {
  value: string;
  onChange: (value: string) => void;
  onSend: () => void;
  disabled?: boolean;
}

export default function Input({ value, onChange, onSend, disabled }: InputProps) {
  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Enter" && !disabled) onSend();
  }

  const canSend = Boolean(value.trim());

  return (
    <div className="composer-shell">
      <div className="composer">
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
            onClick={onSend}
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
