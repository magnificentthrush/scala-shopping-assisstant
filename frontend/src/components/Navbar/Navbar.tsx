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
    <div className="absolute top-4 right-6 z-10 flex items-center gap-4 text-sm">
      <span className="text-gray-400">Hi, {user?.fullName}</span>
      <button onClick={handleLogout} className="text-gray-400 hover:text-white transition-colors">
        Logout
      </button>
    </div>
  );
}