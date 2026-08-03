// Provides authentication state to the whole app.
// Any component can call useAuth() to get the current user and login/logout functions.

import { createContext, useContext, useState, useEffect } from "react";
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
  const [user, setUser] = useState<User | null>(null);
  const [isLoggedIn, setIsLoggedIn] = useState(false);

  // On app load (or page refresh), check localStorage for an existing session
  useEffect(() => {
    if (isAuthenticated()) {
      setUser(getStoredUser());
      setIsLoggedIn(true);
    }
  }, []);

  function handleSetUser(newUser: User | null) {
    setUser(newUser);
    setIsLoggedIn(!!newUser);
  }

  function handleLogout() {
    logoutApi();
    setUser(null);
    setIsLoggedIn(false);
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