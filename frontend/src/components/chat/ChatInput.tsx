import * as React from "react";
import { Brain, Send, Square } from "lucide-react";

import { Textarea } from "@/components/ui/textarea";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";

export function ChatInput() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const {
    sendMessage,
    isStreaming,
    cancelGeneration,
    deepThinkingEnabled,
    setDeepThinkingEnabled,
    inputFocusKey
  } = useChatStore();

  const focusInput = React.useCallback(() => {
    textareaRef.current?.focus({ preventScroll: true });
  }, []);

  const adjustHeight = React.useCallback(() => {
    const element = textareaRef.current;
    if (!element) return;
    element.style.height = "auto";
    element.style.height = `${Math.min(element.scrollHeight, 160)}px`;
  }, []);

  React.useEffect(() => {
    adjustHeight();
  }, [value, adjustHeight]);

  React.useEffect(() => {
    if (inputFocusKey) focusInput();
  }, [inputFocusKey, focusInput]);

  const handleSubmit = async () => {
    if (isStreaming) {
      cancelGeneration();
      focusInput();
      return;
    }
    if (!value.trim()) return;
    const next = value;
    setValue("");
    focusInput();
    await sendMessage(next);
    focusInput();
  };

  const hasContent = value.trim().length > 0;

  return (
    <div className={cn("chat-workspace-composer", isFocused && "is-focused")}>
      <Textarea
        ref={textareaRef}
        value={value}
        onChange={(event) => setValue(event.target.value)}
        placeholder={deepThinkingEnabled ? "输入需要深度分析的问题" : "在 NexusRAG 中提问"}
        className="chat-workspace-composer-input"
        rows={1}
        onFocus={() => setIsFocused(true)}
        onBlur={() => setIsFocused(false)}
        onCompositionStart={() => {
          isComposingRef.current = true;
        }}
        onCompositionEnd={() => {
          isComposingRef.current = false;
        }}
        onKeyDown={(event) => {
          if (event.key === "Enter" && !event.shiftKey) {
            const nativeEvent = event.nativeEvent as KeyboardEvent;
            if (nativeEvent.isComposing || isComposingRef.current || nativeEvent.keyCode === 229) return;
            event.preventDefault();
            handleSubmit();
          }
        }}
        aria-label="聊天输入框"
      />
      <div className="chat-workspace-composer-actions">
        <button
          type="button"
          onClick={() => setDeepThinkingEnabled(!deepThinkingEnabled)}
          disabled={isStreaming}
          aria-pressed={deepThinkingEnabled}
          className={cn("chat-workspace-deep-toggle", deepThinkingEnabled && "is-active")}
          title="深度思考"
        >
          <Brain className="h-4 w-4" />
          <span>深度思考</span>
          {deepThinkingEnabled ? <i aria-hidden="true" /> : null}
        </button>
        <button
          type="button"
          onClick={handleSubmit}
          disabled={!hasContent && !isStreaming}
          aria-label={isStreaming ? "停止生成" : "发送消息"}
          className={cn(
            "chat-workspace-send-button",
            isStreaming && "is-stopping",
            hasContent && !isStreaming && "is-ready"
          )}
        >
          {isStreaming ? <Square className="h-4 w-4" /> : <Send className="h-4 w-4" />}
        </button>
      </div>
    </div>
  );
}
