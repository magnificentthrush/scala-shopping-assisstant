// Top-right circular profile avatar. Shows profile picture if set,
// otherwise the user's initials. Clicking it opens Settings.

import { useAuth } from "../../context/AuthContext";

interface ProfileAvatarProps {
  onOpenSettings: () => void;
}

export default function Navbar({ onOpenSettings }: ProfileAvatarProps) {
  const { user } = useAuth();

  const initials = user?.fullName
    ? user.fullName.trim().split(" ").map((n) => n[0]).slice(0, 2).join("").toUpperCase()
    : "?";

  return (
    <button
      onClick={onOpenSettings}
      className="absolute top-4 right-6 z-10 w-9 h-9 rounded-full overflow-hidden bg-[var(--bg-surface-alt)] border border-[var(--border-color)] flex items-center justify-center text-sm font-semibold text-[var(--text-primary)] hover:opacity-80 transition-opacity"
    >
      {user?.avatarUrl ? (
        <img src={user.avatarUrl} alt="Profile" className="w-full h-full object-cover" />
      ) : (
        initials
      )}
    </button>
  );
}