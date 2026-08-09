import { useState, useEffect, useRef } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { CircleCheck, LoaderCircle, TriangleAlert } from "lucide-react";
import BrandLogo from "../../components/BrandLogo/BrandLogo";
import { apiFetch } from "../../api/client";
import {
  clearPendingVerificationToken,
  getPendingVerificationToken,
} from "../../api/auth";

// Reads ?token= from the URL (Phase 2 — the email link) and falls back to the
// pendingVerificationToken stored at signup time (Phase 1 — no email service),
// then verifies against the backend and renders the real result.
export default function VerifyEmail() {
  const [searchParams] = useSearchParams();
  const [status, setStatus] = useState<"loading" | "success" | "error">("loading");
  const [errorMessage, setErrorMessage] = useState("");
  // StrictMode double-invokes effects in dev; the verify token is single-use on
  // the backend, so don't verify the same token twice (a new ?token= still runs).
  const hasAttempted = useRef(false);
  const attemptedToken = useRef<string | null>(null);

  useEffect(() => {
    const urlToken = searchParams.get("token");
    const token = urlToken || getPendingVerificationToken();
    if (hasAttempted.current && token === attemptedToken.current) return;
    hasAttempted.current = true;
    attemptedToken.current = token;

    let cancelled = false;

    async function verify() {
      if (!token) {
        setErrorMessage(
          "This verification link is invalid or has expired. Please try signing up again."
        );
        setStatus("error");
        return;
      }

      try {
        const data = await apiFetch<{ verified: boolean }>(
          `/api/auth/verify-email?token=${encodeURIComponent(token)}`
        );
        if (cancelled) return;
        clearPendingVerificationToken();
        setStatus(data.verified ? "success" : "error");
      } catch (err: any) {
        if (cancelled) return;
        if (err?.code === "TOKEN_EXPIRED") {
          setErrorMessage(
            "This verification link has expired. Please sign up again to get a new one."
          );
        } else if (err?.code === "TOKEN_INVALID") {
          setErrorMessage(
            "This verification link is invalid or has already been used. Please sign up again."
          );
        } else {
          setErrorMessage(
            err?.message || "Something went wrong while verifying your email. Please try again."
          );
        }
        setStatus("error");
      }
    }

    verify();

    return () => {
      cancelled = true;
    };
  }, [searchParams]);

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
                {errorMessage ||
                  "This link is invalid or has expired. Please try signing up again."}
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
