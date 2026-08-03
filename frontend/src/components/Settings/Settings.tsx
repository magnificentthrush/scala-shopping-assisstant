// Settings modal — edit display name, toggle theme, logout

import { useState } from "react";
import { X, Moon, Sun, LogOut } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";
import { useTheme } from "../../context/ThemeContext";

interface SettingsProps {
  onClose: () => void;
}

export default function Settings({ onClose }: SettingsProps) {
  const { user, setUser, logout } = useAuth();
  const { theme, toggleTheme } = useTheme();
  const navigate = useNavigate();

  const [name, setName] = useState(user?.fullName || "");
  const [saved, setSaved] = useState(false);

  function handleSaveName() {
    if (!user || !name.trim()) return;
    const updatedUser = { ...user, fullName: name.trim() };
    setUser(updatedUser);
    localStorage.setItem("user", JSON.stringify(updatedUser));
    setSaved(true);
    setTimeout(() => setSaved(false), 1500);
  }

  function handleLogout() {
    logout();
    navigate("/login");
  }

  return (
    <div className="fixed inset-0 bg-black/70 flex items-center justify-center z-50">
      <div className="bg-[#0b1330] text-white rounded-2xl p-6 w-96 border border-slate-800 shadow-2xl">
        <div className="flex items-center justify-between mb-5">
          <h3 className="text-lg font-semibold">Settings</h3>
          <button onClick={onClose} className="text-slate-500 hover:text-white transition-colors">
            <X size={18} />
          </button>
        </div>

        {/* Edit display name */}
        <div className="mb-5">
          <label className="block text-sm text-blue-400 mb-1.5">Display name</label>
          <div className="flex gap-2">
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="flex-1 bg-[#05070f] border border-slate-800 rounded-lg px-3 py-2 text-sm text-white outline-none focus:border-blue-500 focus:ring-1 focus:ring-blue-500/40 transition-colors"
            />
            <button
              onClick={handleSaveName}
              className="px-4 py-2 text-sm font-semibold rounded-lg bg-white text-[#05070f] hover:bg-slate-200 transition-colors"
            >
              Save
            </button>
          </div>
          {saved && <p className="text-xs text-green-400 mt-1">Saved!</p>}
        </div>

        {/* Theme toggle */}
        <div className="mb-6">
          <label className="block text-sm text-blue-400 mb-1.5">Theme</label>
          <button
            onClick={toggleTheme}
            className="w-full flex items-center justify-between bg-[#05070f] border border-slate-800 rounded-lg px-3 py-2.5 text-sm text-white hover:border-blue-500/50 transition-colors"
          >
            <span className="flex items-center gap-2">
              {theme === "dark" ? <Moon size={15} /> : <Sun size={15} />}
              {theme === "dark" ? "Dark theme" : "Light theme"}
            </span>
            <span className="text-xs text-slate-500">Tap to switch</span>
          </button>
        </div>

        <div className="border-t border-slate-800 pt-4">
          <button
            onClick={handleLogout}
            className="w-full flex items-center justify-center gap-2 text-red-400 hover:text-red-300 text-sm py-2 transition-colors"
          >
            <LogOut size={15} />
            Logout
          </button>
        </div>
      </div>
    </div>
  );
}