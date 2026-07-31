// Login page where users can enter their email and password
// The backend is not connected yet, so this form currently provides only the UI.
// Authentication will be integrated later using the backend API.

import { useState } from "react";
import { Link } from "react-router-dom";

export default function Login() {
  // State for the form fields
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");

  // Called when the user clicks the "Log In" button
  function handleSubmit(e: React.FormEvent) {
    e.preventDefault(); // Prevent the page from reloading

    // Basic frontend validation
    if (!email || !password) {
      setError("Please fill in both fields.");
      return;
    }

    // TODO: Connect this to the backend /api/auth/login endpoint once it is available
    console.log("Login attempt:", { email, password });
    setError("Backend not connected yet — this is a UI placeholder.");
  }

  return (
    <div className="flex items-center justify-center min-h-[calc(100vh-64px)] bg-gray-50">
      <div className="bg-white p-8 rounded-xl shadow-sm border border-gray-200 w-full max-w-sm">
        <h2 className="text-xl font-bold text-gray-800 mb-1">Welcome back</h2>
        <p className="text-sm text-gray-500 mb-6">Log in to continue shopping</p>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Email input field */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Email
            </label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {/* Password input field */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Password
            </label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {/* Display an error message if one exists */}
          {error && <p className="text-xs text-red-500">{error}</p>}

          <button
            type="submit"
            className="w-full bg-blue-600 text-white rounded-lg py-2 text-sm font-medium hover:bg-blue-700 transition-colors"
          >
            Log In
          </button>
        </form>

        {/* Link to the signup page */}
        <p className="text-sm text-gray-500 mt-4 text-center">
          Don't have an account?{" "}
          <Link to="/signup" className="text-blue-600 hover:underline">
            Sign up
          </Link>
        </p>
      </div>
    </div>
  );
}