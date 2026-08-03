// Login page — connects to the auth API (mocked for now, see api/auth.ts)

import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { LoaderCircle, ShoppingBag } from "lucide-react";
import { login } from "../../api/auth";
import { useAuth } from "../../context/AuthContext";

export default function Login() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  const navigate = useNavigate();
  const { setUser } = useAuth();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");

    if (!email || !password) {
      setError("Please fill in both fields.");
      return;
    }

    setLoading(true);
    try {
      const result = await login(email, password);
      setUser(result.user);
      navigate("/"); // go to chat after successful login
    } catch (err: any) {
      // err.message comes from our mock rejection or ApiError
      setError(err.message || "Login failed. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="flex min-h-dvh items-center justify-center bg-[#0e0e0e] px-4 py-10">
      <section
        aria-labelledby="login-title"
        className="w-full max-w-md rounded-2xl border border-white/10 bg-[#171717] p-6 shadow-2xl shadow-black/30 sm:p-8"
      >
        <div className="mb-7 flex h-11 w-11 items-center justify-center rounded-xl bg-blue-600 text-white shadow-lg shadow-blue-950/40">
          <ShoppingBag aria-hidden="true" size={21} />
        </div>
        <h1 id="login-title" className="text-pretty text-2xl font-bold tracking-tight text-white">
          Welcome Back
        </h1>
        <p className="mt-1 text-sm text-gray-400">Log in to continue shopping smarter.</p>

        <form onSubmit={handleSubmit} className="mt-7 space-y-5">
          <div>
            <label htmlFor="login-email" className="mb-1.5 block text-sm font-medium text-gray-200">
              Email
            </label>
            <input
              id="login-email"
              name="email"
              type="email"
              autoComplete="email"
              spellCheck={false}
              required
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com…"
              disabled={loading}
              aria-describedby={error ? "login-error" : undefined}
              className="w-full rounded-xl border border-white/10 bg-[#222] px-3.5 py-3 text-sm text-white placeholder:text-gray-600 hover:border-white/20 focus-visible:border-blue-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500/30 disabled:opacity-50"
            />
          </div>

          <div>
            <label htmlFor="login-password" className="mb-1.5 block text-sm font-medium text-gray-200">
              Password
            </label>
            <input
              id="login-password"
              name="password"
              type="password"
              autoComplete="current-password"
              required
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              disabled={loading}
              aria-describedby={error ? "login-error" : undefined}
              className="w-full rounded-xl border border-white/10 bg-[#222] px-3.5 py-3 text-sm text-white placeholder:text-gray-600 hover:border-white/20 focus-visible:border-blue-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500/30 disabled:opacity-50"
            />
          </div>

          {error && (
            <p id="login-error" role="alert" aria-live="polite" className="rounded-lg bg-red-950/50 px-3 py-2 text-sm text-red-300">
              {error}
            </p>
          )}

          <button
            type="submit"
            disabled={loading}
            className="flex w-full items-center justify-center gap-2 rounded-xl bg-blue-600 py-3 text-sm font-semibold text-white shadow-lg shadow-blue-950/30 transition-[background-color,box-shadow] hover:bg-blue-500 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400 focus-visible:ring-offset-2 focus-visible:ring-offset-[#171717] disabled:opacity-60"
          >
            {loading && <LoaderCircle aria-hidden="true" size={16} className="animate-spin" />}
            {loading ? "Logging In…" : "Log In"}
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-gray-400">
          Don’t have an account?{" "}
          <Link
            to="/signup"
            className="font-medium text-blue-400 underline-offset-4 hover:text-blue-300 hover:underline focus-visible:rounded-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-400"
          >
            Sign Up
          </Link>
        </p>
      </section>
    </div>
  );
}