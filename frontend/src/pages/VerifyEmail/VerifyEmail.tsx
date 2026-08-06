import { useState, useEffect } from "react";
import { Link } from "react-router-dom";
import { CircleCheck, LoaderCircle, TriangleAlert } from "lucide-react";
import BrandLogo from "../../components/BrandLogo/BrandLogo";

// Placeholder until the backend issues real verification links (see docs/authentication.md).
// There's no link/token to validate yet, so this just renders the success state.
export default function VerifyEmail() {
  const [status, setStatus] = useState<"loading" | "success" | "error">("loading");

  useEffect(() => {
    setStatus("success");
  }, []);

  return (
    <div className="auth-screen">
      <header className="auth-screen__header">
        <BrandLogo compact />
        <Link to="/login" className="button">Log in</Link>
      </header>
      <main className="auth-screen__main">
        <section className="auth-panel auth-panel--centered" aria-live="polite">
        {status === "loading" && (
            <>
              <div className="auth-panel__mark" aria-hidden="true">
                <LoaderCircle size={21} strokeWidth={1.7} />
              </div>
              <h1>Verifying your email</h1>
              <p className="auth-panel__subtitle">This should only take a moment.</p>
            </>
        )}
        {status === "success" && (
          <>
              <div className="auth-panel__mark" aria-hidden="true">
                <CircleCheck size={22} strokeWidth={1.7} />
              </div>
              <h1>Email verified</h1>
              <p className="auth-panel__subtitle">Your account is active and ready to use.</p>
              <Link to="/login" className="button button--primary">Go to login</Link>
          </>
        )}
        {status === "error" && (
          <>
              <div className="auth-panel__mark" aria-hidden="true">
                <TriangleAlert size={22} strokeWidth={1.7} />
              </div>
              <h1>Verification failed</h1>
              <p className="auth-panel__subtitle">
              This link is invalid or has expired. Please try signing up again.
            </p>
              <Link to="/signup" className="button">
              Back to signup
            </Link>
          </>
        )}
        </section>
      </main>
      <footer className="auth-screen__footer">ShopPilot · Product discovery, simplified.</footer>
    </div>
  );
}