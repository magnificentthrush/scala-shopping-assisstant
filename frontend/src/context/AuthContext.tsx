// Provides authentication state to the whole app.
// Any component can call useAuth() to get the current user and login/logout functions.

import { createContext, useContext, useState } from "react";
import type { ReactNode } from "react";
import type { User } from "../types";
import { getStoredUser, isAuthenticated, logout as logoutApi } from "../api/auth";

interface AuthContextType {
  user: User | null;
  isLoggedIn: boolean;
  setUser: (user: User | null) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function AuthProvider({ children }: { children: ReactNode }) {
  // Read storage during the initial render so protected routes never see a
  // temporary logged-out state while a valid local session is being restored.
  const [user, setUser] = useState<User | null>(() =>
    isAuthenticated() ? getStoredUser() : null
  );
  const isLoggedIn = user !== null;

  function handleSetUser(newUser: User | null) {
    setUser(newUser);
  }

  function handleLogout() {
    logoutApi();
    setUser(null);
  }

  return (
    <AuthContext.Provider value={{ user, isLoggedIn, setUser: handleSetUser, logout: handleLogout }}>
      {children}
    </AuthContext.Provider>
  );
}

// Custom hook — use this in any component instead of useContext directly
export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) throw new Error("useAuth must be used inside AuthProvider");
  return context;
}