import * as React from "react";
import { differenceInCalendarDays, isValid } from "date-fns";
import {
  BookOpen,
  LogOut,
  MessageSquare,
  MoreHorizontal,
  Pencil,
  Plus,
  Search,
  Settings,
  Trash2,
  Workflow
} from "lucide-react";
import { useNavigate } from "react-router-dom";

import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger
} from "@/components/ui/dropdown-menu";
import { BrandMark } from "@/components/common/BrandMark";
import { Loading } from "@/components/common/Loading";
import { UserAvatar } from "@/components/common/UserAvatar";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";

interface SidebarProps {
  isOpen: boolean;
  onClose: () => void;
}

export function Sidebar({ isOpen, onClose }: SidebarProps) {
  const {
    sessions,
    currentSessionId,
    isLoading,
    sessionsLoaded,
    createSession,
    deleteSession,
    renameSession,
    selectSession,
    fetchSessions
  } = useChatStore();
  const navigate = useNavigate();
  const { user, logout } = useAuthStore();
  const [query, setQuery] = React.useState("");
  const [renamingId, setRenamingId] = React.useState<string | null>(null);
  const [renameValue, setRenameValue] = React.useState("");
  const [deleteTarget, setDeleteTarget] = React.useState<{
    id: string;
    title: string;
  } | null>(null);
  const renameInputRef = React.useRef<HTMLInputElement | null>(null);

  React.useEffect(() => {
    if (sessions.length === 0) {
      fetchSessions().catch(() => null);
    }
  }, [fetchSessions, sessions.length]);

  const filteredSessions = React.useMemo(() => {
    const keyword = query.trim().toLowerCase();
    if (!keyword) return sessions;
    return sessions.filter((session) => {
      const title = (session.title || "新对话").toLowerCase();
      return title.includes(keyword) || session.id.toLowerCase().includes(keyword);
    });
  }, [query, sessions]);

  const groupedSessions = React.useMemo(() => {
    const now = new Date();
    const groups = new Map<string, typeof filteredSessions>();
    const order: string[] = [];

    const resolveLabel = (value?: string) => {
      const parsed = value ? new Date(value) : now;
      const date = isValid(parsed) ? parsed : now;
      const diff = Math.max(0, differenceInCalendarDays(now, date));
      if (diff === 0) return "今天";
      if (diff <= 7) return "7 天内";
      if (diff <= 30) return "30 天内";
      return "更早";
    };

    filteredSessions.forEach((session) => {
      const label = resolveLabel(session.lastTime);
      if (!groups.has(label)) {
        groups.set(label, []);
        order.push(label);
      }
      groups.get(label)?.push(session);
    });

    return order.map((label) => ({
      label,
      items: groups.get(label) || []
    }));
  }, [filteredSessions]);

  React.useEffect(() => {
    if (renamingId) {
      renameInputRef.current?.focus();
      renameInputRef.current?.select();
    }
  }, [renamingId]);

  const startRename = (id: string, title: string) => {
    setRenamingId(id);
    setRenameValue(title || "新对话");
  };

  const cancelRename = () => {
    setRenamingId(null);
    setRenameValue("");
  };

  const commitRename = async () => {
    if (!renamingId) return;
    const nextTitle = renameValue.trim();
    if (!nextTitle) {
      cancelRename();
      return;
    }
    const currentTitle = sessions.find((session) => session.id === renamingId)?.title || "新对话";
    if (nextTitle !== currentTitle) {
      await renameSession(renamingId, nextTitle);
    }
    cancelRename();
  };

  const openAdminPage = (path: string) => {
    window.open(path, "_blank", "noopener,noreferrer");
    onClose();
  };

  return (
    <>
      <div
        className={cn("chat-workspace-sidebar-overlay", isOpen && "is-open")}
        onClick={onClose}
        aria-hidden="true"
      />
      <aside className={cn("chat-workspace-sidebar", isOpen && "is-open")}>
        <div className="chat-workspace-sidebar-primary">
          <button
            type="button"
            className="chat-workspace-new-chat"
            onClick={() => {
              createSession().catch(() => null);
              navigate("/chat");
              onClose();
            }}
          >
            <Plus className="h-4 w-4" />
            新建对话
          </button>

          {user?.role === "admin" ? (
            <div className="chat-workspace-nav-section">
              <span>工作台</span>
              <button type="button" onClick={() => openAdminPage("/admin/knowledge")}>
                <BrandMark className="h-[18px] w-[18px] shrink-0" />
                知识库
              </button>
              <button type="button" onClick={() => openAdminPage("/admin/ingestion")}>
                <Workflow className="h-4 w-4 shrink-0" />
                数据通道
              </button>
            </div>
          ) : null}

          <label className="chat-workspace-session-search">
            <Search className="h-4 w-4" />
            <input
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索对话"
              aria-label="搜索对话"
            />
          </label>
        </div>

        <div className="chat-workspace-session-area sidebar-scroll">
          {sessions.length === 0 && (!sessionsLoaded || isLoading) ? (
            <div className="chat-workspace-sidebar-state">
              <Loading label="加载会话中" />
            </div>
          ) : filteredSessions.length === 0 ? (
            <div className="chat-workspace-sidebar-state">
              <MessageSquare className="h-9 w-9" />
              <p>暂无对话记录</p>
            </div>
          ) : (
            groupedSessions.map((group) => (
              <section key={group.label} className="chat-workspace-session-group">
                <span>{group.label}</span>
                {group.items.map((session) => (
                  <div
                    key={session.id}
                    className={cn(
                      "chat-workspace-session-row group",
                      currentSessionId === session.id && "is-active"
                    )}
                    role="button"
                    tabIndex={0}
                    onClick={() => {
                      if (renamingId === session.id) return;
                      if (renamingId) cancelRename();
                      selectSession(session.id).catch(() => null);
                      navigate(`/chat/${session.id}`);
                      onClose();
                    }}
                    onKeyDown={(event) => {
                      if (event.key === "Enter") {
                        selectSession(session.id).catch(() => null);
                        navigate(`/chat/${session.id}`);
                        onClose();
                      }
                    }}
                  >
                    <MessageSquare className="h-3.5 w-3.5 shrink-0" />
                    {renamingId === session.id ? (
                      <input
                        ref={renameInputRef}
                        value={renameValue}
                        onChange={(event) => setRenameValue(event.target.value)}
                        onClick={(event) => event.stopPropagation()}
                        onKeyDown={(event) => {
                          if (event.key === "Enter") {
                            event.preventDefault();
                            commitRename().catch(() => null);
                          }
                          if (event.key === "Escape") {
                            event.preventDefault();
                            cancelRename();
                          }
                        }}
                        onBlur={() => commitRename().catch(() => null)}
                        className="chat-workspace-rename-input"
                      />
                    ) : (
                      <span className="min-w-0 flex-1 truncate">{session.title || "新对话"}</span>
                    )}
                    <DropdownMenu>
                      <DropdownMenuTrigger asChild>
                        <button
                          type="button"
                          className="chat-workspace-session-more"
                          onClick={(event) => event.stopPropagation()}
                          aria-label="会话操作"
                        >
                          <MoreHorizontal className="h-4 w-4" />
                        </button>
                      </DropdownMenuTrigger>
                      <DropdownMenuContent align="start" className="min-w-[128px]">
                        <DropdownMenuItem
                          onClick={(event) => {
                            event.stopPropagation();
                            startRename(session.id, session.title || "新对话");
                          }}
                        >
                          <Pencil className="mr-2 h-4 w-4" />
                          重命名
                        </DropdownMenuItem>
                        <DropdownMenuItem
                          onClick={(event) => {
                            event.stopPropagation();
                            setDeleteTarget({ id: session.id, title: session.title || "新对话" });
                          }}
                          className="text-rose-600 focus:text-rose-600"
                        >
                          <Trash2 className="mr-2 h-4 w-4" />
                          删除
                        </DropdownMenuItem>
                      </DropdownMenuContent>
                    </DropdownMenu>
                  </div>
                ))}
              </section>
            ))
          )}
        </div>

        <div className="chat-workspace-sidebar-footer">
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <button type="button" className="chat-workspace-user-button" aria-label="用户菜单">
                <UserAvatar
                  user={user}
                  className="h-8 w-8 border-[#d9e0e9] bg-[#e9effc] text-[#2858c4]"
                />
                <span className="chat-workspace-user-meta">
                  <strong>{user?.username || "用户"}</strong>
                  <small>{user?.role === "admin" ? "管理员" : "成员"}</small>
                </span>
                <MoreHorizontal className="h-4 w-4" />
              </button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" side="top" sideOffset={8} className="w-48">
              {user?.role === "admin" ? (
                <DropdownMenuItem onClick={() => openAdminPage("/admin")}>
                  <Settings className="mr-2 h-4 w-4" />
                  管理后台
                </DropdownMenuItem>
              ) : null}
              <DropdownMenuItem asChild>
                <a
                  href="https://www.hjs123.xin/projects/nexusrag"
                  target="_blank"
                  rel="noreferrer"
                >
                  <BookOpen className="mr-2 h-4 w-4" />
                  官方文档
                </a>
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => logout()} className="text-rose-600 focus:text-rose-600">
                <LogOut className="mr-2 h-4 w-4" />
                退出登录
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>
        </div>
      </aside>

      <AlertDialog
        open={Boolean(deleteTarget)}
        onOpenChange={(open) => {
          if (!open) setDeleteTarget(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除该会话？</AlertDialogTitle>
            <AlertDialogDescription>
              [{deleteTarget?.title || "该会话"}] 将被永久删除，无法恢复。
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction
              onClick={() => {
                if (!deleteTarget) return;
                const target = deleteTarget;
                const isCurrent = currentSessionId === target.id;
                setDeleteTarget(null);
                deleteSession(target.id)
                  .then(() => {
                    if (isCurrent) navigate("/chat");
                  })
                  .catch(() => null);
              }}
            >
              删除
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </>
  );
}
