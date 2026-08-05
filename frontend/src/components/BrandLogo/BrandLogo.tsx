import blackLogo from "../../assets/black-logo.png";
import whiteLogo from "../../assets/white-logo.png";

interface BrandLogoProps {
  className?: string;
  compact?: boolean;
}

export default function BrandLogo({ className = "", compact = false }: BrandLogoProps) {
  return (
    <span className={`brand-logo ${compact ? "brand-logo--compact" : ""} ${className}`.trim()}>
      <img className="brand-logo__light" src={blackLogo} alt="ShopPilot" />
      <img className="brand-logo__dark" src={whiteLogo} alt="ShopPilot" />
    </span>
  );
}
