import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { login } from "../../api/auth";
import { useAuth } from "../../context/AuthContext";
import { isValidEmail } from "../../utils/validation";
import BrandLogo from "../../components/BrandLogo/BrandLogo";

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
    if (!isValidEmail(email)) {
      setError("Please enter a valid email address.");
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
    <div className="auth-screen">
      <header className="auth-screen__header">
        <BrandLogo compact />
        <Link to="/signup" className="button">Sign up</Link>
      </header>

      <main className="auth-screen__main">
        <section className="auth-panel" aria-labelledby="login-title">
          <h1 id="login-title">Welcome back</h1>
          <p className="auth-panel__subtitle">Log in to continue with your shopping assistant.</p>

          <form onSubmit={handleSubmit} className="auth-form" noValidate>
            <div className="auth-field">
              <label htmlFor="login-email">Email address</label>
            <input
                id="login-email"
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="you@example.com"
              disabled={loading}
                className="auth-input"
                autoComplete="email"
            />
          </div>
            <div className="auth-field">
              <label htmlFor="login-password">Password</label>
            <input
                id="login-password"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              placeholder="••••••••"
              disabled={loading}
                className="auth-input"
                autoComplete="current-password"
            />
          </div>

            {error ? <p className="auth-error" role="alert">{error}</p> : null}

          <button
            type="submit"
            disabled={loading}
            className="auth-submit"
          >
            {loading ? "Logging in..." : "Log In"}
          </button>
        </form>

          <p className="auth-switch">
            Don&apos;t have an account? <Link to="/signup">Sign up</Link>
          </p>
        </section>
      </main>

      <footer className="auth-screen__footer">ShopPilot · Product discovery, simplified.</footer>
    </div>
  );
}