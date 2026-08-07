import { Avatar } from "@/components/common/Avatar";
import { ADMIN_AVATAR_SRC } from "@/components/common/BrandMark";

type AvatarUser = {
  userId?: string;
  username?: string;
  role?: string;
  avatar?: string;
} | null;

interface UserAvatarProps {
  user?: AvatarUser;
  className?: string;
  fallbackName?: string;
}

export function resolveUserAvatar(user?: AvatarUser) {
  if (user?.role === "admin") {
    return ADMIN_AVATAR_SRC;
  }
  return user?.avatar?.trim() || undefined;
}

export function UserAvatar({ user, className, fallbackName = "用户" }: UserAvatarProps) {
  const name = user?.username || user?.userId || fallbackName;
  return <Avatar name={name} src={resolveUserAvatar(user)} className={className} />;
}
