import * as React from "react";
import * as AvatarPrimitive from "@radix-ui/react-avatar";

import { cn } from "@/lib/utils";

interface AvatarProps {
  name: string;
  src?: string;
  className?: string;
}

export function Avatar({ name, src, className }: AvatarProps) {
  const fallback = React.useMemo(() => {
    if (!name) return "?";
    const parts = name.trim().split(" ");
    const letters = parts.map((part) => part[0]).join("");
    return letters.slice(0, 2).toUpperCase();
  }, [name]);

  return (
    <AvatarPrimitive.Root
      className={cn(
        "relative inline-flex h-9 w-9 shrink-0 items-center justify-center overflow-hidden rounded-full border border-border bg-muted text-xs font-semibold text-muted-foreground",
        className
      )}
    >
      {src ? (
        <AvatarPrimitive.Image src={src} alt={name} className="block h-full w-full object-cover" />
      ) : null}
      <AvatarPrimitive.Fallback className="flex h-full w-full select-none items-center justify-center">
        {fallback}
      </AvatarPrimitive.Fallback>
    </AvatarPrimitive.Root>
  );
}
