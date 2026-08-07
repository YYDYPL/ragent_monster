import * as React from "react";

import { SourcesPanel } from "@/components/chat/SourcesPanel";
import { Header } from "@/components/layout/Header";
import { Sidebar } from "@/components/layout/Sidebar";

interface MainLayoutProps {
  children: React.ReactNode;
}

export function MainLayout({ children }: MainLayoutProps) {
  const [sidebarOpen, setSidebarOpen] = React.useState(false);
  const [contextOpen, setContextOpen] = React.useState(false);

  return (
    <div className="chat-workspace-shell">
      <Header
        onToggleSidebar={() => setSidebarOpen((prev) => !prev)}
        onToggleContext={() => setContextOpen((prev) => !prev)}
      />
      <Sidebar isOpen={sidebarOpen} onClose={() => setSidebarOpen(false)} />
      <main className="chat-workspace-main">{children}</main>
      <SourcesPanel mobileOpen={contextOpen} onMobileOpenChange={setContextOpen} />
    </div>
  );
}
