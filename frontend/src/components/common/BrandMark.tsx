import { cn } from "@/lib/utils";

export const NEXUSRAG_MARK_SRC = "/branding/nexusrag-mark.png";
export const NEXUSRAG_LOGO_SRC = "/branding/nexusrag-logo.png";
export const ADMIN_AVATAR_SRC = "/branding/admin-avatar.jpg";

interface BrandMarkProps {
  variant?: "mark" | "logo";
  className?: string;
  alt?: string;
}

export function BrandMark({
  variant = "mark",
  className,
  alt = "NexusRAG"
}: BrandMarkProps) {
  return (
    <img
      src={variant === "logo" ? NEXUSRAG_LOGO_SRC : NEXUSRAG_MARK_SRC}
      alt={alt}
      className={cn("block object-contain", className)}
    />
  );
}
