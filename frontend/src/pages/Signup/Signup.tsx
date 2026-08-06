import { useState } from "react";
import { Link } from "react-router-dom";
import { Check, X, Eye, EyeOff } from "lucide-react";
import { register } from "../../api/auth";
import { isValidEmail, isValidPassword } from "../../utils/validation";
import BrandLogo from "../../components/BrandLogo/BrandLogo";

export default function Signup() {
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [submittedEmail, setSubmittedEmail] = useState<string | null>(null);

  const passwordChecks = [
    { label: "At least 6 characters", valid: password.length >= 6 },
    { label: "At least one number", valid: /\d/.test(password) },
    { label: "At least one uppercase letter", valid: /[A-Z]/.test(password) },
  ];
  const showChecklist = password.length > 0;

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError("");

    if (!name || !email || !password) {
      setError("Please fill in all fields.");
      return;
    }
    if (!isValidEmail(email)) {
      setError("Please enter a valid email address.");
      return;
    }
    const passwordCheck = isValidPassword(password);
    if (!passwordCheck.valid) {
      setError(passwordCheck.message!);
      return;
    }

    setLoading(true);
    try {
      await register(name, email, password);
      setSubmittedEmail(email);
    } catch (err: any) {
      setError(err.message || "Signup failed. Please try again.");
    } finally {
      setLoading(false);
    }
  }

  if (submittedEmail) {
    return (
      <div className="auth-screen">
        <header className="auth-screen__header">
          <BrandLogo compact />
          <Link to="/login" className="button">Log in</Link>
        </header>
        <main className="auth-screen__main">
          <section className="auth-panel auth-panel--centered" aria-labelledby="check-email-title">
            <div className="auth-panel__mark" aria-hidden="true">
              <Check size={21} strokeWidth={1.8} />
            </div>
            <h1 id="check-email-title">Check your email</h1>
            <p className="auth-panel__subtitle">
              We sent a verification link to <strong>{submittedEmail}</strong>. Open it to activate
              your account, then log in.
            </p>
            <Link to="/login" className="button button--primary">Back to login</Link>
          </section>
        </main>
        <footer className="auth-screen__footer">You can close this page after verifying your email.</footer>
      </div>
    );
  }

  return (
    <div className="auth-screen">
      <header className="auth-screen__header">
        <BrandLogo compact />
        <Link to="/login" className="button">Log in</Link>
      </header>

      <main className="auth-screen__main">
        <section className="auth-panel" aria-labelledby="signup-title">
          <h1 id="signup-title">Create your account</h1>
          <p className="auth-panel__subtitle">Start finding better products with ShopPilot.</p>

          <form onSubmit={handleSubmit} className="auth-form" noValidate>
            <div className="auth-field">
              <label htmlFor="signup-name">Full name</label>
            <input
                id="signup-name"
              type="text"
              value={name}
              onChange={(e) => setName(e.target.value)}
              placeholder="Jane Doe"
              disabled={loading}
                className="auth-input"
                autoComplete="name"
            />
          </div>
            <div className="auth-field">
              <label htmlFor="signup-email">Email address</label>
            <input
                id="signup-email"
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
              <label htmlFor="signup-password">Password</label>
              <div className="auth-input-wrap">
              <input
                  id="signup-password"
                type={showPassword ? "text" : "password"}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="At least 6 characters"
                disabled={loading}
                  className="auth-input auth-input--with-action"
                  autoComplete="new-password"
              />
              <button
                type="button"
                onClick={() => setShowPassword((s) => !s)}
                  className="icon-button auth-input-action"
                  aria-label={showPassword ? "Hide password" : "Show password"}
              >
                  {showPassword ? <EyeOff size={17} strokeWidth={1.7} /> : <Eye size={17} strokeWidth={1.7} />}
              </button>
            </div>

            {showChecklist && (
              <div className="auth-checklist">
                {passwordChecks.map((check) => (
                    <div key={check.label} className={`auth-check ${check.valid ? "auth-check--valid" : ""}`}>
                    {check.valid ? (
                        <Check size={13} aria-hidden="true" />
                    ) : (
                        <X size={13} aria-hidden="true" />
                    )}
                    <span>{check.label}</span>
                  </div>
                ))}
              </div>
            )}
          </div>

            {error ? <p className="auth-error" role="alert">{error}</p> : null}

          <button
            type="submit"
            disabled={loading}
            className="auth-submit"
          >
            {loading ? "Creating account..." : "Sign Up"}
          </button>
        </form>

          <p className="auth-switch">
            Already have an account? <Link to="/login">Log in</Link>
          </p>
        </section>
      </main>

      <footer className="auth-screen__footer">By continuing, you agree to use ShopPilot responsibly.</footer>
    </div>
  );
}