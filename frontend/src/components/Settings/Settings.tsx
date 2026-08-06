import { useState } from "react";
import { Moon, Sun, LogOut } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";
import { useTheme } from "../../context/ThemeContext";
import figmaCloseIcon from "../../assets/figma-icons/header-edit.svg";

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
    <div className="dialog-backdrop" role="presentation" onMouseDown={onClose}>
      <div
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="settings-title"
        onMouseDown={(event) => event.stopPropagation()}
      >
        <div className="dialog__header">
          <h2 id="settings-title">Settings</h2>
          <button type="button" onClick={onClose} className="icon-button" aria-label="Close settings">
            <img src={figmaCloseIcon} alt="" className="figma-icon" />
          </button>
        </div>

        <div className="dialog__body">
          <section className="settings-section">
            <label className="settings-section__label" htmlFor="display-name">Display name</label>
            <div className="settings-field-row">
            <input
                id="display-name"
              value={name}
              onChange={(e) => setName(e.target.value)}
                className="text-field"
            />
            <button
                type="button"
              onClick={handleSaveName}
                className="button button--primary"
                disabled={!name.trim()}
            >
              Save
            </button>
          </div>
            {saved ? <p className="saved-message" role="status">Name saved</p> : null}
          </section>

          <section className="settings-section">
            <span className="settings-section__label">Appearance</span>
          <button
              type="button"
            onClick={toggleTheme}
              className="settings-toggle"
          >
              <span className="settings-toggle__label">
                {theme === "dark" ? <Moon size={17} strokeWidth={1.7} /> : <Sun size={17} strokeWidth={1.7} />}
                {theme === "dark" ? "Dark mode" : "Light mode"}
            </span>
              <small>Switch</small>
          </button>
          </section>

          <section className="settings-section">
          <button
              type="button"
            onClick={handleLogout}
              className="button button--danger"
          >
              <LogOut size={16} strokeWidth={1.7} />
              Log out
          </button>
          </section>
        </div>
      </div>
    </div>
  );
}