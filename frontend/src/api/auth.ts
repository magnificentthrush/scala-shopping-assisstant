// Authentication against our own backend (see docs/authPlan.md §6).
// The Scala backend's /api/auth/* endpoints are live and verified (steps
// 1-14), so auth always talks to the real API — no localStorage simulation.

import { apiFetch } from "./client";
import type { User } from "../types";

// Phase-1 (no email service) fallback: when the backend has no RESEND_API_KEY
// it returns the raw verification token so the frontend can complete
// verification without an inbox. VerifyEmail reads this when the URL has no
// ?token= query param.
export const PENDING_VERIFICATION_TOKEN_KEY = "pendingVerificationToken";

export function getPendingVerificationToken(): string | null {
  return localStorage.getItem(PENDING_VERIFICATION_TOKEN_KEY);
}

export function clearPendingVerificationToken() {
  localStorage.removeItem(PENDING_VERIFICATION_TOKEN_KEY);
}

export async function register(fullName: string, email: string, password: string) {
  const data = await apiFetch<{ user: User; needsVerification: boolean; verificationToken?: string }>(
    "/api/auth/register",
    {
      method: "POST",
      body: JSON.stringify({ fullName, email, password }),
    }
  );

  // Phase-1 fallback: no email service configured means the backend includes
  // the raw verificationToken for us to use directly. Never treat register as
  // logged-in — no saveAuth here.
  if (data.verificationToken) {
    localStorage.setItem(PENDING_VERIFICATION_TOKEN_KEY, data.verificationToken);
  }

  return { user: data.user, needsVerification: data.needsVerification };
}

export async function login(email: string, password: string) {
  // apiFetch throws ApiError with the backend's `code` (e.g. 403
  // EMAIL_NOT_VERIFIED) — propagate it untouched so Login can show a distinct
  // "verify your email" message instead of a generic failure.
  const data = await apiFetch<{ user: User; token: string }>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
  saveAuth(data.user, data.token);
  return { user: data.user, token: data.token };
}

export async function logout() {
  localStorage.removeItem("token");
  localStorage.removeItem("user");
}

function saveAuth(user: User, token: string) {
  localStorage.setItem("token", token);
  localStorage.setItem("user", JSON.stringify(user));
}

export function getStoredUser(): User | null {
  const raw = localStorage.getItem("user");
  return raw ? JSON.parse(raw) : null;
}

export function getStoredToken(): string | null {
  return localStorage.getItem("token");
}

export function isAuthenticated(): boolean {
  return !!getStoredToken(); //"ad234cxf.." -> return True but if "null", the !!null becomes false (a javaScript trick)
}

export async function restoreSession(): Promise<User | null> {
  return getStoredUser();
}
