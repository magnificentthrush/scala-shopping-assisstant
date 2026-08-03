import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
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
      navigate("/");
    } catch (err: any) {
      setError(err.message || "Login failed. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div
      className="relative flex items-center justify-center min-h-screen px-4 overflow-hidden"
      style={{
        background:
          "radial-gradient(ellipse 80% 60% at 50% 0%, #0d1b3d 0%, #05070f 55%, #000000 100%)",
      }}
    >
      {/* ambient glow accents, no boxes */}
      <div
        className="pointer-events-none absolute -top-40 left-1/2 -translate-x-1/2 w-[600px] h-[600px] rounded-full blur-3xl opacity-30"
        style={{ background: "radial-gradient(circle, #2b4bff 0%, transparent 70%)" }}
      />
      <div
        className="pointer-events-none absolute bottom-0 right-0 w-[400px] h-[400px] rounded-full blur-3xl opacity-20"
        style={{ background: "radial-gradient(circle, #1e2a5e 0%, transparent 70%)" }}
      />

      <div className="relative w-full max-w-sm">
        {/* Brand header */}
        <div className="flex items-center gap-4 mb-12">
          <div
            className="flex items-center justify-center w-16 h-16 rounded-2xl text-3xl font-bold text-white shrink-0"
            style={{
              background: "linear-gradient(135deg, #2b4bff 0%, #0f1b4d 100%)",
              boxShadow: "0 6px 28px rgba(43, 75, 255, 0.5)",
            }}
          >
            S
          </div>
          <span
            className="text-4xl font-extrabold tracking-tight"
            style={{
              backgroundImage: "linear-gradient(135deg, #ffffff 0%, #93a8ff 100%)",
              WebkitBackgroundClip: "text",
              WebkitTextFillColor: "transparent",
              backgroundClip: "text",
            }}
          >
            ShopPilot
          </span>
        </div>

        <h2 className="text-3xl font-semibold text-white mb-2 tracking-tight">
          Welcome back
        </h2>
        <p className="text-base text-slate-400 mb-8">Log in to continue shopping</p>

        <form onSubmit={handleSubmit} className="space-y-5">
          <div>
            <label className="block text-sm font-medium text-slate-300 mb-2">
              Email
            </label>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
              disabled={loading}
              className="w-full bg-[#0b1330] border border-slate-700 rounded-xl px-4 py-3 text-base text-white placeholder-slate-600 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500/30 transition-colors disabled:opacity-50"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-slate-300 mb-2">
              Password
            </label>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              disabled={loading}
              className="w-full bg-[#0b1330] border border-slate-700 rounded-xl px-4 py-3 text-base text-white placeholder-slate-600 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-500/30 transition-colors disabled:opacity-50"
            />
          </div>

          {error && <p className="text-sm text-red-400">{error}</p>}

          <button
            type="submit"
            disabled={loading}
            className="w-full mt-2 rounded-full py-3.5 text-base font-medium text-white transition-all disabled:opacity-50"
            style={{
              background: "linear-gradient(135deg, #2b4bff 0%, #0f1b4d 100%)",
              boxShadow: "0 4px 24px rgba(43, 75, 255, 0.35)",
            }}
            onMouseEnter={(e) =>
              (e.currentTarget.style.boxShadow = "0 6px 32px rgba(43, 75, 255, 0.55)")
            }
            onMouseLeave={(e) =>
              (e.currentTarget.style.boxShadow = "0 4px 24px rgba(43, 75, 255, 0.35)")
            }
          >
            {loading ? "Logging in..." : "Log In"}
          </button>
        </form>

        <p className="text-base text-slate-500 mt-6 text-center">
          Don't have an account?{" "}
          <Link to="/signup" className="text-white hover:text-blue-400 transition-colors">
            Sign up
          </Link>
        </p>
      </div>
    </div>
  );
}