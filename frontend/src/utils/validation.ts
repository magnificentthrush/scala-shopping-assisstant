// Simple, reliable email format check

export function isValidEmail(email: string): boolean {
  const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
  return emailRegex.test(email.trim());
}

// Password policy (docs/authPlan.md §5) — enforced here AND independently on
// the backend during register: at least 8 characters, at least 1 digit, at
// least 1 uppercase letter. Keep this rule in sync with
// AuthService.isValidPassword.
export function isValidPassword(password: string): { valid: boolean; message?: string } {
  if (password.length < 8) {
    return { valid: false, message: "Password must be at least 8 characters." };
  }
  if (!/\d/.test(password)) {
    return { valid: false, message: "Password must include at least one number." };
  }
  if (!/[A-Z]/.test(password)) {
    return { valid: false, message: "Password must include at least one uppercase letter." };
  }
  return { valid: true };
}