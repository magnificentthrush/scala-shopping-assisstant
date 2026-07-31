// This component contains only the chat input field and send button
// It is separated from ChatWidget to keep the main component clean and easier to maintain

import { Send } from "lucide-react";

interface InputProps {
  value: string;                              // Current text in the input field
  onChange: (value: string) => void;          // Called whenever the user types
  onSend: () => void;                         // Called when the Send button is clicked
  disabled?: boolean;                         // Disable the input while waiting for a response
}

export default function Input({ value, onChange, onSend, disabled }: InputProps) {
  // Send the message when the Enter key is pressed
  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "Enter" && !disabled) {
      onSend();
    }
  }

  return (
    <div className="border-t bg-white p-3 flex items-center gap-2">
      <input
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={handleKeyDown}
        disabled={disabled}
        placeholder="e.g. waterproof hiking shoes under $120"
        className="flex-1 border border-gray-300 rounded-full px-4 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
      />
      <button
        onClick={onSend}
        disabled={disabled}
        className="bg-blue-600 text-white rounded-full p-2 hover:bg-blue-700 disabled:opacity-50 transition-colors"
      >
        <Send size={18} />
      </button>
    </div>
  );
}