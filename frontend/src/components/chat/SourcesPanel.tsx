import * as React from "react";
import { ArrowLeft, ExternalLink, X } from "lucide-react";

import { BrandMark } from "@/components/common/BrandMark";
import { fileExt, isExternal, SourceIcon, sourceLabel } from "@/components/chat/SourceIcon";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/stores/authStore";
import { useChatStore } from "@/stores/chatStore";
import type { SourceRef } from "@/types";

interface SourcesPanelProps {
  mobileOpen: boolean;
  onMobileOpenChange: (open: boolean) => void;
}

function openSource(source: SourceRef) {
  if (isExternal(source) && source.url) {
    window.open(source.url, "_blank", "noopener,noreferrer");
    return;
  }
  window.open(`/preview/doc/${source.docId}`, "_blank", "noopener,noreferrer");
}

function metaLabel(source: SourceRef) {
  const base = sourceLabel(source);
  if (!isExternal(source)) {
    const ext = fileExt(source);
    return ext ? `${base} · ${ext}` : base;
  }
  return base;
}

function sourceKey(source: SourceRef) {
  return `${source.docId || "external"}:${source.url || ""}`;
}

export function SourcesPanel({ mobileOpen, onMobileOpenChange }: SourcesPanelProps) {
  const openedSourceMessageId = useChatStore((state) => state.openedSourceMessageId);
  const messages = useChatStore((state) => state.messages);
  const closeSourcesPanel = useChatStore((state) => state.closeSourcesPanel);
  const user = useAuthStore((state) => state.user);

  const selectedSources = React.useMemo(
    () => messages.find((message) => message.id === openedSourceMessageId)?.sources ?? [],
    [messages, openedSourceMessageId]
  );
  const allSources = React.useMemo(() => {
    const unique = new Map<string, SourceRef>();
    messages.forEach((message) => {
      message.sources?.forEach((source) => {
        const key = sourceKey(source);
        if (!unique.has(key)) unique.set(key, source);
      });
    });
    return Array.from(unique.values());
  }, [messages]);

  const showingCurrent = openedSourceMessageId != null;
  const shownSources = showingCurrent ? selectedSources : allSources;
  const overlayOpen = mobileOpen || showingCurrent;

  const closeOverlay = React.useCallback(() => {
    closeSourcesPanel();
    onMobileOpenChange(false);
  }, [closeSourcesPanel, onMobileOpenChange]);

  React.useEffect(() => {
    if (!overlayOpen) return;
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") closeOverlay();
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [overlayOpen, closeOverlay]);

  return (
    <>
      <button
        type="button"
        className={cn("chat-workspace-context-overlay", overlayOpen && "is-open")}
        onClick={closeOverlay}
        aria-label="关闭知识上下文"
      />
      <aside className={cn("chat-workspace-context-panel", overlayOpen && "is-open")}>
        <div className="chat-workspace-context-tabs">
          <button
            type="button"
            className={cn(!showingCurrent && "is-active")}
            onClick={closeSourcesPanel}
          >
            上下文
          </button>
          <button
            type="button"
            className={cn(showingCurrent && "is-active")}
            disabled={!showingCurrent}
          >
            当前来源
          </button>
          <button
            type="button"
            className="chat-workspace-context-close"
            onClick={closeOverlay}
            aria-label="关闭知识上下文"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        <div className="chat-workspace-context-scroll sidebar-scroll">
          <section className="chat-workspace-context-summary">
            <div className="chat-workspace-context-brand-icon">
              <BrandMark className="h-10 w-10" />
            </div>
            <div>
              <strong>{showingCurrent ? "回答引用" : "会话知识上下文"}</strong>
              <span>{shownSources.length} 个关联文档</span>
            </div>
          </section>

          {showingCurrent ? (
            <button
              type="button"
              className="chat-workspace-context-back"
              onClick={closeSourcesPanel}
            >
              <ArrowLeft className="h-3.5 w-3.5" />
              返回会话上下文
            </button>
          ) : null}

          <section className="chat-workspace-context-section">
            <div className="chat-workspace-context-title">
              <strong>{showingCurrent ? "引用文档" : "已使用的知识"}</strong>
              <span>{shownSources.length}</span>
            </div>

            {shownSources.length === 0 ? (
              <div className="chat-workspace-context-empty">
                <BrandMark className="h-12 w-12 opacity-40" />
                <p>当前会话暂无引用来源</p>
              </div>
            ) : (
              <ul className="chat-workspace-source-list">
                {shownSources.map((source, index) => (
                  <li key={`${sourceKey(source)}:${index}`}>
                    <button type="button" onClick={() => openSource(source)} title={source.docName}>
                      <span className="chat-workspace-source-icon">
                        <SourceIcon source={source} className="h-4 w-4" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <strong>{source.docName || "未命名文档"}</strong>
                        <small>{metaLabel(source)}</small>
                        {source.excerpt ? <p>{source.excerpt}</p> : null}
                      </span>
                      <ExternalLink className="h-3.5 w-3.5 shrink-0" />
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          {user?.role === "admin" ? (
            <section className="chat-workspace-context-section">
              <div className="chat-workspace-context-title">
                <strong>知识库</strong>
              </div>
              <button
                type="button"
                className="chat-workspace-manage-kb"
                onClick={() => window.open("/admin/knowledge", "_blank", "noopener,noreferrer")}
              >
                <BrandMark className="h-8 w-8" />
                <span>
                  <strong>NexusRAG 知识库</strong>
                  <small>管理文档与检索内容</small>
                </span>
                <ExternalLink className="h-4 w-4" />
              </button>
            </section>
          ) : null}
        </div>
      </aside>
    </>
  );
}
