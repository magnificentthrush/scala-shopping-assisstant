import { useState, useEffect } from "react";
import { Link } from "react-router-dom";
import { supabase } from "../../lib/supabase";

export default function VerifyEmail() {
  const [status, setStatus] = useState<"loading" | "success" | "error">("loading");

  useEffect(() => {
    checkSession();
  }, []);

  async function checkSession() {
    const { data, error } = await supabase.auth.getSession();
    if (error || !data.session) {
      setStatus("error");
      return;
    }
    setStatus("success");
  }

  return (
    <div className="flex items-center justify-center min-h-screen bg-[var(--bg-app)] px-4">
      <div className="bg-[var(--bg-sidebar)] p-8 rounded-2xl shadow-xl border border-[var(--border-color)] w-full max-w-sm text-center">
        {status === "loading" && (
          <p className="text-sm text-[var(--text-secondary)]">Verifying your email...</p>
        )}
        {status === "success" && (
          <>
            <h2 className="text-xl font-bold text-[var(--text-primary)] mb-2">Email verified!</h2>
            <p className="text-sm text-[var(--text-secondary)] mb-4">
              Your account is now active. You can log in.
            </p>
            <Link
              to="/login"
              className="inline-block bg-[var(--btn-bg)] text-[var(--btn-text)] rounded-xl px-4 py-2 text-sm font-medium hover:bg-[var(--btn-bg-hover)] transition-colors"
            >
              Go to Login
            </Link>
          </>
        )}
        {status === "error" && (
          <>
            <h2 className="text-xl font-bold text-[var(--text-primary)] mb-2">Verification failed</h2>
            <p className="text-sm text-[var(--text-secondary)] mb-4">
              This link is invalid or has expired. Please try signing up again.
            </p>
            <Link to="/signup" className="text-sm text-[var(--text-primary)] hover:underline">
              Back to signup
            </Link>
          </>
        )}
      </div>
    </div>
  );
}