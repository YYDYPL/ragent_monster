import * as React from "react";
import { Brain, ChevronDown } from "lucide-react";

import { BrandMark } from "@/components/common/BrandMark";
import { UserAvatar } from "@/components/common/UserAvatar";
import { FeedbackButtons } from "@/components/chat/FeedbackButtons";
import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { RecommendedQuestions } from "@/components/chat/RecommendedQuestions";
import { RecommendedQuestionsButton } from "@/components/chat/RecommendedQuestionsButton";
import { SourcesButton } from "@/components/chat/SourcesButton";
import { ThinkingIndicator } from "@/components/chat/ThinkingIndicator";
import { cn } from "@/lib/utils";
import { useAuthStore } from "@/stores/authStore";
import type { Message } from "@/types";

interface MessageItemProps {
  message: Message;
}

function formatMessageTime(value?: string) {
  if (!value) return "刚刚";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "刚刚";
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false
  }).format(date);
}

export const MessageItem = React.memo(function MessageItem({ message }: MessageItemProps) {
  const user = useAuthStore((state) => state.user);
  const isUser = message.role === "user";
  const showFeedback =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    message.id &&
    !message.id.startsWith("assistant-");
  const isThinking = Boolean(message.isThinking);
  const hasSources =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    (message.sources?.length ?? 0) > 0;
  const canRecommend =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    Boolean(message.id) &&
    (message.messageStatus ?? "NORMAL") === "NORMAL" &&
    !message.id.startsWith("assistant-");
  const [thinkingExpanded, setThinkingExpanded] = React.useState(false);
  const hasThinking = Boolean(message.thinking && message.thinking.trim().length > 0);
  const hasContent = message.content.trim().length > 0;
  const isWaiting = message.status === "streaming" && !isThinking && !hasContent;
  const timeLabel = formatMessageTime(message.createdAt);

  if (isUser) {
    return (
      <article className="chat-workspace-message chat-workspace-user-message">
        <UserAvatar
          user={user}
          className="chat-workspace-message-avatar border-[#d8e0ea] bg-[#edf2f8] text-[#2858c4]"
        />
        <div className="min-w-0">
          <div className="chat-workspace-message-meta">
            <strong>{user?.username || "用户"}</strong>
            <time>{timeLabel}</time>
          </div>
          <p className="chat-workspace-user-copy">{message.content}</p>
        </div>
      </article>
    );
  }

  const thinkingDuration = message.thinkingDuration ? `${message.thinkingDuration} 秒` : "";
  return (
    <article className="chat-workspace-message chat-workspace-assistant-message">
      <div className="chat-workspace-assistant-avatar">
        <BrandMark className="h-8 w-8" />
      </div>
      <div className="min-w-0 space-y-4">
        <div className="chat-workspace-message-meta">
          <strong>NexusRAG</strong>
          <span>AI 助手</span>
          <time>{timeLabel}</time>
        </div>

        {isThinking ? (
          <ThinkingIndicator content={message.thinking} duration={message.thinkingDuration} />
        ) : null}
        {!isThinking && hasThinking ? (
          <div className="overflow-hidden rounded-md border border-[#cbd9ef] bg-[#f5f8fd]">
            <button
              type="button"
              onClick={() => setThinkingExpanded((prev) => !prev)}
              className="flex w-full items-center gap-2 px-3 py-2.5 text-left transition-colors hover:bg-[#eaf0fa]"
            >
              <div className="flex flex-1 items-center gap-2">
                <div className="flex h-7 w-7 items-center justify-center rounded-md bg-[#dce7f8]">
                  <Brain className="h-4 w-4 text-[#2858c4]" />
                </div>
                <span className="text-sm font-medium text-[#2858c4]">深度思考</span>
                {thinkingDuration ? (
                  <span className="rounded bg-[#dce7f8] px-2 py-0.5 text-xs text-[#2858c4]">
                    {thinkingDuration}
                  </span>
                ) : null}
              </div>
              <ChevronDown
                className={cn(
                  "h-4 w-4 text-[#5575ad] transition-transform",
                  thinkingExpanded && "rotate-180"
                )}
              />
            </button>
            {thinkingExpanded ? (
              <div className="border-t border-[#dce5f2] px-4 pb-4">
                <div className="mt-3 whitespace-pre-wrap text-sm leading-relaxed text-[#435879]">
                  {message.thinking}
                </div>
              </div>
            ) : null}
          </div>
        ) : null}

        <div className="space-y-3">
          {isWaiting ? (
            <div className="ai-wait" aria-label="思考中">
              <span className="ai-wait-dots" aria-hidden="true">
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
              </span>
            </div>
          ) : null}
          {hasContent ? <MarkdownRenderer content={message.content} /> : null}
          {message.status === "error" ? <p className="text-xs text-rose-500">生成已中断。</p> : null}
          {showFeedback || hasSources || canRecommend ? (
            <div className="flex flex-wrap items-center gap-2 border-t border-[#edf0f4] pt-2">
              {showFeedback ? (
                <FeedbackButtons
                  messageId={message.id}
                  feedback={message.feedback ?? null}
                  content={message.content}
                  alwaysVisible
                />
              ) : null}
              {hasSources ? <SourcesButton messageId={message.id} sources={message.sources!} /> : null}
              {canRecommend ? <RecommendedQuestionsButton message={message} /> : null}
            </div>
          ) : null}
          {canRecommend ? <RecommendedQuestions message={message} /> : null}
        </div>
      </div>
    </article>
  );
});
