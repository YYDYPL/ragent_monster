import * as React from "react";
import { ArrowUpRight, BookOpen, Brain, Check, Lightbulb, Send, Square } from "lucide-react";

import { BrandMark } from "@/components/common/BrandMark";
import { cn } from "@/lib/utils";
import { listSampleQuestions } from "@/services/sampleQuestionService";
import { useChatStore } from "@/stores/chatStore";

type PromptPreset = {
  id?: string;
  title: string;
  description: string;
  prompt: string;
  icon: React.ComponentType<{ className?: string }>;
};

const PRESET_ICONS = [BookOpen, Check, Lightbulb];

const DEFAULT_PRESETS: PromptPreset[] = [
  {
    title: "内容总结",
    description: "提炼关键信息与行动项",
    prompt: "请帮我总结以下内容，并列出 3-5 条要点：",
    icon: BookOpen
  },
  {
    title: "任务拆解",
    description: "规划步骤、优先级和里程碑",
    prompt: "请把下面需求拆解为步骤，并给出优先级和里程碑：",
    icon: Check
  },
  {
    title: "方案探索",
    description: "比较多个方案与关键取舍",
    prompt: "围绕以下主题给出 5-8 个方案，并说明各自优缺点：",
    icon: Lightbulb
  }
];

export function WelcomeScreen() {
  const [value, setValue] = React.useState("");
  const [isFocused, setIsFocused] = React.useState(false);
  const [promptPresets, setPromptPresets] = React.useState<PromptPreset[]>(DEFAULT_PRESETS);
  const isComposingRef = React.useRef(false);
  const textareaRef = React.useRef<HTMLTextAreaElement | null>(null);
  const { sendMessage, isStreaming, cancelGeneration, deepThinkingEnabled, setDeepThinkingEnabled } =
    useChatStore();

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
    let active = true;
    listSampleQuestions()
      .then((data) => {
        if (!active || !data?.length) return;
        const mapped = data
          .filter((item) => item.question?.trim())
          .slice(0, 3)
          .map((item, index) => {
            const question = item.question.trim();
            return {
              id: item.id,
              title: item.title?.trim() || (question.length > 12 ? `${question.slice(0, 12)}...` : question),
              description: item.description?.trim() || "使用企业知识开始对话",
              prompt: question,
              icon: PRESET_ICONS[index % PRESET_ICONS.length]
            };
          });
        if (mapped.length) setPromptPresets(mapped);
      })
      .catch(() => null);
    return () => {
      active = false;
    };
  }, []);

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
    <div className="chat-workspace-welcome sidebar-scroll">
      <div className="chat-workspace-welcome-inner">
        <div className="chat-workspace-welcome-heading">
          <div className="chat-workspace-welcome-mark">
            <BrandMark className="h-14 w-14" />
          </div>
          <div>
            <span>企业知识助手</span>
            <h1>欢迎使用 NexusRAG</h1>
            <p>连接企业知识，获取有依据、可追溯的回答。</p>
          </div>
        </div>

        <div className={cn("chat-workspace-welcome-composer", isFocused && "is-focused")}>
          <textarea
            ref={textareaRef}
            value={value}
            onChange={(event) => setValue(event.target.value)}
            placeholder={deepThinkingEnabled ? "输入需要深度分析的问题" : "向企业知识库提问"}
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
            aria-label="发送消息"
          />
          <div>
            <button
              type="button"
              onClick={() => setDeepThinkingEnabled(!deepThinkingEnabled)}
              disabled={isStreaming}
              aria-pressed={deepThinkingEnabled}
              className={cn("chat-workspace-deep-toggle", deepThinkingEnabled && "is-active")}
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

        <section className="chat-workspace-prompt-section">
          <div className="chat-workspace-prompt-title">
            <strong>建议从这里开始</strong>
            <span>基于常用企业知识任务</span>
          </div>
          <div className="chat-workspace-prompt-grid">
            {promptPresets.map((preset) => {
              const Icon = preset.icon;
              return (
                <button
                  key={preset.id ?? preset.title}
                  type="button"
                  onClick={() => {
                    if (isStreaming) return;
                    setValue(preset.prompt);
                    focusInput();
                  }}
                  disabled={isStreaming}
                >
                  <span className="chat-workspace-prompt-icon">
                    <Icon className="h-4 w-4" />
                  </span>
                  <span className="min-w-0 flex-1">
                    <strong>{preset.title}</strong>
                    <small>{preset.description}</small>
                  </span>
                  <ArrowUpRight className="h-4 w-4 shrink-0" />
                </button>
              );
            })}
          </div>
        </section>
      </div>
    </div>
  );
}
