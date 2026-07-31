// Signup page where new users can create an account
// The backend is not connected yet, so this form currently provides only the UI.

import { useState } from "react";
import { Link } from "react-router-dom";

export default function Signup() {
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");

  function handleSubmit(e: React.FormEvent) {
    e.preventDefault();

    // Validate that all required fields are filled
    if (!name || !email || !password) {
      setError("Please fill in all fields.");
      return;
    }

    // Validate the minimum password length
    if (password.length < 6) {
      setError("Password must be at least 6 characters.");
      return;
    }

    // TODO: Connect this to the backend /api/auth/signup endpoint once it is available
    console.log("Signup attempt:", { name, email, password });
    setError("Backend not connected yet — this is a UI placeholder.");
  }

  return (
    <div className="flex items-center justify-center min-h-[calc(100vh-64px)] bg-gray-50">
      <div className="bg-white p-8 rounded-xl shadow-sm border border-gray-200 w-full max-w-sm">
        <h2 className="text-xl font-bold text-gray-800 mb-1">Create an account</h2>
        <p className="text-sm text-gray-500 mb-6">Sign up to start shopping smarter</p>

        <form onSubmit={handleSubmit} className="space-y-4">
          {/* Full name input field */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Full Name
            </label>
            <input
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Jane Doe"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

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
              placeholder="At least 6 characters"
              className="w-full border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            />
          </div>

          {/* Display an error message if validation fails */}
          {error && <p className="text-xs text-red-500">{error}</p>}

          <button
            type="submit"
            className="w-full bg-blue-600 text-white rounded-lg py-2 text-sm font-medium hover:bg-blue-700 transition-colors"
          >
            Sign Up
          </button>
        </form>

        {/* Link to the login page */}
        <p className="text-sm text-gray-500 mt-4 text-center">
          Already have an account?{" "}
          <Link to="/login" className="text-blue-600 hover:underline">
            Log in
          </Link>
        </p>
      </div>
    </div>
  );
}