// Handles registration, login, logout, and JWT storage.
// Mocked for now — remembers names by email so login shows the correct name.

import { apiFetch } from "./client";
import type { AuthResponse, User } from "../types";

const USE_MOCK_API = true;

// Mock "database" — remembers which name goes with which email during this session
const mockUserDirectory: Record<string, string> = {};

function mockDelay<T>(value: T, ms = 600): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), ms));
}

// Turns "ada.lovelace@example.com" into "Ada.lovelace" as a last-resort display name
function nameFromEmail(email: string): string {
  const localPart = email.split("@")[0];
  return localPart.charAt(0).toUpperCase() + localPart.slice(1);
}

export async function register(
  fullName: string,
  email: string,
  password: string
): Promise<AuthResponse> {
  if (USE_MOCK_API) {
    if (email === "taken@example.com") {
      return Promise.reject({ status: 409, code: "EMAIL_TAKEN", message: "Email already registered" });
    }
    mockUserDirectory[email] = fullName; // remember this name for future logins

    const mockResponse: AuthResponse = {
      user: { id: crypto.randomUUID(), fullName, email },
      token: "mock-jwt-" + crypto.randomUUID(),
    };
    const result = await mockDelay(mockResponse);
    saveAuth(result);
    return result;
  }

  const result = await apiFetch<AuthResponse>("/api/auth/register", {
    method: "POST",
    body: JSON.stringify({ fullName, email, password }),
  });
  saveAuth(result);
  return result;
}

export async function login(
  email: string,
  password: string
): Promise<AuthResponse> {
  if (USE_MOCK_API) {
    if (password === "wrong") {
      return Promise.reject({ status: 401, code: "INVALID_CREDENTIALS", message: "Invalid email or password" });
    }

    // Use the name from a previous signup if we have one, otherwise derive from email
    const fullName = mockUserDirectory[email] || nameFromEmail(email);

    const mockResponse: AuthResponse = {
      user: { id: crypto.randomUUID(), fullName, email },
      token: "mock-jwt-" + crypto.randomUUID(),
    };
    const result = await mockDelay(mockResponse);
    saveAuth(result);
    return result;
  }

  const result = await apiFetch<AuthResponse>("/api/auth/login", {
    method: "POST",
    body: JSON.stringify({ email, password }),
  });
  saveAuth(result);
  return result;
}

export function logout(): void {
  localStorage.removeItem("token");
  localStorage.removeItem("user");
}

function saveAuth(auth: AuthResponse) {
  localStorage.setItem("token", auth.token);
  localStorage.setItem("user", JSON.stringify(auth.user));
}

export function getStoredUser(): User | null {
  const raw = localStorage.getItem("user");
  return raw ? JSON.parse(raw) : null;
}

export function getStoredToken(): string | null {
  return localStorage.getItem("token");
}

export function isAuthenticated(): boolean {
  return !!getStoredToken();
}