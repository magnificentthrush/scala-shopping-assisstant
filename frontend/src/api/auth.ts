// Authentication via Supabase Auth

import { supabase } from "../lib/supabase";
import type { User } from "../types";

function mapSupabaseUser(supabaseUser: any): User {
  return {
    id: supabaseUser.id,
    fullName: supabaseUser.user_metadata?.full_name || "",
    email: supabaseUser.email,
  };
}

export async function register(fullName: string, email: string, password: string) {
  const { data, error } = await supabase.auth.signUp({
    email,
    password,
    options: {
      data: { full_name: fullName },
      emailRedirectTo: `${window.location.origin}/verify-email`,
    },
  });

  if (error) {
    throw new Error(error.message);
  }

  // Supabase quirk: if the email already exists, signUp still "succeeds" but
  // returns a user with an empty identities array instead of a clear error.
  // This is how we detect "this email is already registered".
  if (data.user && data.user.identities && data.user.identities.length === 0) {
    throw new Error("An account with this email already exists. Please log in instead.");
  }

  return { user: data.user ? mapSupabaseUser(data.user) : null, needsVerification: !data.session };
}

export async function login(email: string, password: string) {
  const { data, error } = await supabase.auth.signInWithPassword({ email, password });

  if (error) {
    if (error.message.toLowerCase().includes("email not confirmed")) {
      throw new Error("Please verify your email before logging in. Check your inbox for the link.");
    }
    throw new Error(error.message);
  }

  const user = mapSupabaseUser(data.user);
  saveAuth(user, data.session.access_token);
  return { user, token: data.session.access_token };
}

export async function logout() {
  await supabase.auth.signOut();
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
  const { data } = await supabase.auth.getSession();
  if (data.session) {
    const user = mapSupabaseUser(data.session.user);
    saveAuth(user, data.session.access_token);
    return user;
  }
  return null;
}