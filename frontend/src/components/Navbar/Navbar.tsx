// Top navigation bar displaying the application name and authentication links

import { Link } from "react-router-dom";

export default function Navbar() {
  return (
    <header className="w-full bg-white border-b border-gray-200 shadow-sm px-4 py-3 flex items-center justify-between">
      <h1 className="text-lg font-bold text-gray-800">
        🛍️ Scala AI Shopping Assistant
      </h1>

      <div className="flex gap-4 text-sm">
        <Link to="/login" className="text-gray-600 hover:text-blue-600">
          Log In
        </Link>
        <Link to="/signup" className="text-gray-600 hover:text-blue-600">
          Sign Up
        </Link>
      </div>
    </header>
  );
}