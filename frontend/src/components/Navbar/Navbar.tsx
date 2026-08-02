// Top navigation bar — shows Login/Signup when logged out, user name + Logout when logged in

import { Link, useNavigate } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";

export default function Navbar() {
  const { isLoggedIn, user, logout } = useAuth();
  const navigate = useNavigate();

  function handleLogout() {
    logout();
    navigate("/login");
  }

  return (
    <header className="w-full bg-white border-b border-gray-200 shadow-sm px-4 py-3 flex items-center justify-between">
      <h1 className="text-lg font-bold text-gray-800">🛍️ ShopPilot</h1>

      <div className="flex items-center gap-4 text-sm">
        {isLoggedIn ? (
          <>
            <span className="text-gray-600">Hi, {user?.fullName}</span>
            <button onClick={handleLogout} className="text-gray-600 hover:text-blue-600">
              Logout
            </button>
          </>
        ) : (
          <>
            <Link to="/login" className="text-gray-600 hover:text-blue-600">Log In</Link>
            <Link to="/signup" className="text-gray-600 hover:text-blue-600">Sign Up</Link>
          </>
        )}
      </div>
    </header>
  );
}