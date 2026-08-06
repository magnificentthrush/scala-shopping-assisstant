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
      type="button"
      onClick={onOpenSettings}
      className="profile-avatar"
      aria-label="Open profile settings"
      title={user?.fullName || "Profile settings"}
    >
      {user?.avatarUrl ? (
        <img src={user.avatarUrl} alt="" />
      ) : (
        initials
      )}
    </button>
  );
}