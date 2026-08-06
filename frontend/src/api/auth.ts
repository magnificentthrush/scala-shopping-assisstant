// Authentication against our own backend (see docs/authentication.md).
// USE_MOCK_API mirrors the pattern in api/conversations.ts: while the Scala
// backend's /api/auth/* endpoints don't exist yet, auth is simulated entirely
// in localStorage so signup/login keep working end-to-end in the browser.

import { apiFetch } from "./client";
import type { User } from "../types";

const USE_MOCK_API = true;

const MOCK_USERS_KEY = "mock_users";

interface MockUser {
  id: string;
  fullName: string;
  email: string;
  password: string;
}

function loadMockUsers(): MockUser[] {
  const raw = localStorage.getItem(MOCK_USERS_KEY);
  return raw ? JSON.parse(raw) : [];
}

function saveMockUsers(users: MockUser[]) {
  localStorage.setItem(MOCK_USERS_KEY, JSON.stringify(users));
}

function toUser(mockUser: MockUser): User {
  return { id: mockUser.id, fullName: mockUser.fullName, email: mockUser.email };
}

export async function register(fullName: string, email: string, password: string) {
  if (USE_MOCK_API) {
    const users = loadMockUsers();
    if (users.some((u) => u.email.toLowerCase() === email.toLowerCase())) {
      throw new Error("An account with this email already exists. Please log in instead.");
    }

    const mockUser: MockUser = { id: crypto.randomUUID(), fullName, email, password };
    users.push(mockUser);
    saveMockUsers(users);

    return { user: toUser(mockUser), needsVerification: true };
  }

  const data = await apiFetch<{ user: User; token: string }>("/api/auth/register", {
    method: "POST",
    body: JSON.stringify({ fullName, email, password }),
  });
  saveAuth(data.user, data.token);
  return { user: data.user, needsVerification: false };
}

export async function login(email: string, password: string) {
  if (USE_MOCK_API) {
    const users = loadMockUsers();
    const mockUser = users.find(
      (u) => u.email.toLowerCase() === email.toLowerCase() && u.password === password
    );
    if (!mockUser) {
      throw new Error("Invalid email or password.");
    }

    const user = toUser(mockUser);
    const token = `mock-token-${mockUser.id}`;
    saveAuth(user, token);
    return { user, token };
  }

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
  return !!getStoredToken();
}

export async function restoreSession(): Promise<User | null> {
  return getStoredUser();
}
