// Chat input — compact composer for shopping requests

import { Send } from "lucide-react";

interface InputProps {
  value: string;
  onChange: (value: string) => void;
  onSend: () => void;
  disabled?: boolean;
}

export default function Input({ value, onChange, onSend, disabled }: InputProps) {
  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Enter" && !disabled) {
      e.preventDefault();
      onSend();
    }
  }

  return (
    <div className="px-3 pb-[max(1rem,env(safe-area-inset-bottom))] pt-2 sm:px-6 sm:pb-6">
      <form
        onSubmit={(event) => {
          event.preventDefault();
          onSend();
        }}
        className="mx-auto max-w-3xl"
      >
        <div className="relative flex items-center gap-2 rounded-2xl border border-gray-700 bg-[#242424] p-2 shadow-xl shadow-black/20 transition-[border-color,box-shadow] hover:border-gray-600 focus-within:border-blue-500 focus-within:ring-2 focus-within:ring-blue-500/20">
          <label htmlFor="chat-message" className="sr-only">Message ShopPilot</label>
          <input
            id="chat-message"
            name="message"
            type="text"
            autoComplete="off"
            value={value}
            onChange={(e) => onChange(e.target.value)}
            onKeyDown={handleKeyDown}
            disabled={disabled}
            placeholder="Describe what you’re shopping for…"
            className="min-w-0 flex-1 bg-transparent px-2 py-1.5 text-sm text-gray-100 placeholder-gray-500 outline-none disabled:opacity-50"
          />
          <button
            type="submit"
            aria-label="Send message"
            disabled={disabled || !value.trim()}
            className="shrink-0 rounded-xl bg-blue-600 p-2.5 text-white transition-colors hover:bg-blue-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-300 disabled:bg-gray-700 disabled:text-gray-500 disabled:opacity-70"
          >
            <Send aria-hidden="true" size={16} />
          </button>
        </div>
        <p className="mt-2 hidden text-center text-xs text-gray-600 sm:block">
          ShopPilot can make mistakes. Confirm product details before purchasing.
        </p>
      </form>
    </div>
  );
}