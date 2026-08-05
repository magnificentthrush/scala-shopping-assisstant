import type { ReactNode } from "react";
import { Navigate } from "react-router-dom";
import { useAuth } from "../../context/AuthContext";
import BrandLogo from "../BrandLogo/BrandLogo";

export default function ProtectedRoute({ children }: { children: ReactNode }) {
  const { isLoggedIn, loading } = useAuth();

  if (loading) {
    return (
      <div className="app-loading" role="status" aria-label="Restoring your ShopPilot session">
        <div className="app-loading__content">
          <BrandLogo />
          <div className="app-loading__dot" aria-hidden="true" />
        </div>
      </div>
    );
  }

  if (!isLoggedIn) {
    return <Navigate to="/login" replace />;
  }

  return <>{children}</>;
}