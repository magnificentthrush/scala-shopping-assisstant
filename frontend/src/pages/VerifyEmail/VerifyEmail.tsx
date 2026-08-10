import { useState, useEffect } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { CircleCheck, LoaderCircle, TriangleAlert } from "lucide-react";
import BrandLogo from "../../components/BrandLogo/BrandLogo";
import { ApiError, apiFetch } from "../../api/client";
import {
  clearPendingVerificationToken,
  getPendingVerificationToken,
} from "../../api/auth";

type VerifyOutcome =
  | { status: "success" }
  | { status: "error"; message: string };

// Module-level cache so React StrictMode remounts (new component instance /
// fresh refs) share one in-flight request. Verification tokens are single-use
// on the backend — a second HTTP call would get TOKEN_INVALID.
const verifyByToken = new Map<string, Promise<VerifyOutcome>>();

function verifyEmailToken(token: string): Promise<VerifyOutcome> {
  const existing = verifyByToken.get(token);
  if (existing) return existing;

  const request = (async (): Promise<VerifyOutcome> => {
    try {
      const data = await apiFetch<{ verified: boolean }>(
        `/api/auth/verify-email?token=${encodeURIComponent(token)}`
      );
      clearPendingVerificationToken();
      return data.verified
        ? { status: "success" }
        : {
            status: "error",
            message:
              "This verification link is invalid or has expired. Please try signing up again.",
          };
    } catch (err: unknown) {
      const code = err instanceof ApiError ? err.code : undefined;
      const message =
        code === "TOKEN_EXPIRED"
          ? "This verification link has expired. Please sign up again to get a new one."
          : code === "TOKEN_INVALID"
            ? "This verification link is invalid or has already been used. Please sign up again."
            : err instanceof Error
              ? err.message
              : "Something went wrong while verifying your email. Please try again.";
      return { status: "error", message };
    }
  })();

  verifyByToken.set(token, request);
  return request;
}

// Reads ?token= from the URL (Phase 2 — the email link) and falls back to the
// pendingVerificationToken stored at signup time (Phase 1 — no email service),
// then verifies against the backend and renders the real result.
export default function VerifyEmail() {
  const [searchParams] = useSearchParams();
  const [status, setStatus] = useState<"loading" | "success" | "error">("loading");
  const [errorMessage, setErrorMessage] = useState("");

  useEffect(() => {
    const urlToken = searchParams.get("token");
    const token = urlToken || getPendingVerificationToken();

    if (!token) {
      setErrorMessage(
        "This verification link is invalid or has expired. Please try signing up again."
      );
      setStatus("error");
      return;
    }

    let cancelled = false;
    verifyEmailToken(token).then((outcome) => {
      if (cancelled) return;
      if (outcome.status === "success") {
        setStatus("success");
      } else {
        setErrorMessage(outcome.message);
        setStatus("error");
      }
    });

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
