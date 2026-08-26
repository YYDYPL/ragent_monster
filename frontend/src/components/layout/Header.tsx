import { Menu, MessageSquare, PanelRight } from "lucide-react";

import { BrandMark } from "@/components/common/BrandMark";
import { UserAvatar } from "@/components/common/UserAvatar";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/stores/authStore";

interface HeaderProps {
  onToggleSidebar: () => void;
  onToggleContext: () => void;
}

export function Header({ onToggleSidebar, onToggleContext }: HeaderProps) {
  const user = useAuthStore((state) => state.user);

  return (
    <header className="chat-workspace-topbar">
      <div className="chat-workspace-brand">
        <button
          type="button"
          className="chat-workspace-icon-button chat-workspace-mobile-menu"
          onClick={onToggleSidebar}
          aria-label="打开会话导航"
        >
          <Menu className="h-[18px] w-[18px]" />
        </button>
        <BrandMark className="h-9 w-9" />
        <div className="min-w-0">
          <strong>NexusRAG</strong>
          <span>企业知识工作台</span>
        </div>
      </div>

      <nav className="chat-workspace-product-nav" aria-label="产品导航">
        <button type="button" className="is-active">
          <MessageSquare className="h-4 w-4" />
          智能问答
        </button>
      </nav>

      <div className="chat-workspace-topbar-actions">
        <button
          type="button"
          className={cn("chat-workspace-icon-button", "chat-workspace-context-trigger")}
          onClick={onToggleContext}
          aria-label="打开知识上下文"
          title="知识上下文"
        >
          <PanelRight className="h-[18px] w-[18px]" />
        </button>
        <UserAvatar
          user={user}
          className="h-8 w-8 border-[#d9e0e9] bg-[#e9effc] text-[#2858c4]"
        />
      </div>
    </header>
  );
}
