// Top bar — dark theme, minimal (name + logout only, sidebar carries the branding now)

import { useNavigate } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";

export default function Navbar() {
  const { isLoggedIn, user, logout } = useAuth();
  const navigate = useNavigate();

  if (!isLoggedIn) return null; // hide on login/signup pages

  function handleLogout() {
    logout();
    navigate("/login");
  }

  return (
    <header className="fixed right-3 top-3 z-10 flex max-w-[calc(100%-4.5rem)] items-center gap-2 rounded-full border border-white/10 bg-[#171717]/90 px-2 py-1.5 text-sm shadow-lg shadow-black/20 backdrop-blur sm:right-5 sm:top-4 sm:gap-3 sm:px-3">
      <span className="min-w-0 truncate text-gray-400">
        Hi, <span className="text-gray-200">{user?.fullName}</span>
      </span>
      <button
        type="button"
        onClick={handleLogout}
        className="shrink-0 rounded-full px-2 py-1 text-gray-400 transition-colors hover:bg-white/10 hover:text-white focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400"
      >
        Log Out
      </button>
    </header>
  );
}