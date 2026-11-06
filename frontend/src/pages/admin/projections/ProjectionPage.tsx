import { useCallback, useEffect, useMemo, useState } from "react";
import { AlertTriangle, CheckCircle2, Clock3, RefreshCw, RotateCcw, Search, ServerCog } from "lucide-react";
import { toast } from "sonner";

import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue
} from "@/components/ui/select";
import {
  getProjectionSummary,
  getProjections,
  getGraphWorkspaces,
  validateGraphWorkspace,
  enableGraphWorkspace,
  disableGraphWorkspace,
  rebuildProjections,
  reconcileProjections,
  retryProjection,
  type ProjectionState,
  type ProjectionSummary,
  type GraphWorkspace
} from "@/services/projectionService";
import type { PageResult } from "@/services/knowledgeService";
import { getErrorMessage } from "@/utils/error";

const PAGE_SIZE = 20;

const statusClass: Record<string, string> = {
  SUCCEEDED: "border-emerald-200 bg-emerald-50 text-emerald-700",
  BLOCKED: "border-red-200 bg-red-50 text-red-700",
  RUNNING: "border-blue-200 bg-blue-50 text-blue-700",
  WAIT_EXTERNAL: "border-violet-200 bg-violet-50 text-violet-700",
  RETRY_WAIT: "border-amber-200 bg-amber-50 text-amber-700",
  PENDING: "border-slate-200 bg-slate-50 text-slate-700"
};

const formatTime = (value?: string | null) => {
  if (!value) return "—";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
};

export function ProjectionPage() {
  const [summary, setSummary] = useState<ProjectionSummary | null>(null);
  const [page, setPage] = useState<PageResult<ProjectionState> | null>(null);
  const [workspaces, setWorkspaces] = useState<GraphWorkspace[]>([]);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [target, setTarget] = useState("ALL");
  const [status, setStatus] = useState("ALL");
  const [kbInput, setKbInput] = useState("");
  const [docInput, setDocInput] = useState("");
  const [kbId, setKbId] = useState("");
  const [docId, setDocId] = useState("");

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [nextSummary, nextPage, nextWorkspaces] = await Promise.all([
        getProjectionSummary(),
        getProjections({
          current,
          size: PAGE_SIZE,
          target: target === "ALL" ? undefined : target,
          status: status === "ALL" ? undefined : status,
          kbId: kbId || undefined,
          docId: docId || undefined
        }),
        getGraphWorkspaces()
      ]);
      setSummary(nextSummary);
      setPage(nextPage);
      setWorkspaces(nextWorkspaces);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载搜索投影失败"));
    } finally {
      setLoading(false);
    }
  }, [current, docId, kbId, status, target]);

  useEffect(() => {
    void load();
  }, [load]);

  const pending = useMemo(() => {
    if (!summary) return 0;
    return Object.entries(summary.counts)
      .filter(([key]) => !key.endsWith(":SUCCEEDED"))
      .reduce((sum, [, value]) => sum + Number(value || 0), 0);
  }, [summary]);

  const blocked = useMemo(() => {
    if (!summary) return 0;
    return Object.entries(summary.counts)
      .filter(([key]) => key.endsWith(":BLOCKED"))
      .reduce((sum, [, value]) => sum + Number(value || 0), 0);
  }, [summary]);

  const runBatch = async (kind: "rebuild" | "reconcile") => {
    try {
      const payload = target === "ALL" ? {} : { target: target as "ELASTICSEARCH" | "LIGHTRAG" };
      const result = kind === "rebuild"
        ? await rebuildProjections(payload)
        : await reconcileProjections(payload);
      toast.success(`${kind === "rebuild" ? "重建" : "对账"}批次已创建：${result.batchId}`);
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, "创建批任务失败"));
    }
  };

  const handleRetry = async (item: ProjectionState) => {
    try {
      await retryProjection(item.id);
      toast.success("任务已重新排队");
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, "重试失败"));
    }
  };

  const workspaceAction = async (workspace: GraphWorkspace, action: "validate" | "enable" | "disable") => {
    try {
      if (action === "validate") await validateGraphWorkspace(workspace.kbId);
      if (action === "enable") await enableGraphWorkspace(workspace.kbId);
      if (action === "disable") await disableGraphWorkspace(workspace.kbId);
      toast.success(action === "validate" ? "workspace 校验完成" : action === "enable" ? "workspace 已启用并开始全量投影" : "workspace 清理任务已提交");
      await load();
    } catch (error) {
      toast.error(getErrorMessage(error, "workspace 操作失败"));
    }
  };

  const cards = [
    { label: "投影总数", value: summary?.total ?? 0, icon: <CheckCircle2 className="h-5 w-5 text-emerald-600" /> },
    { label: "当前积压", value: pending, icon: <Clock3 className="h-5 w-5 text-amber-600" /> },
    { label: "阻断任务", value: blocked, icon: <AlertTriangle className="h-5 w-5 text-red-600" /> },
    {
      label: "SLO 超时（ES / 图谱）",
      value: `${summary?.esSloBreaches ?? 0} / ${summary?.graphSloBreaches ?? 0}`,
      icon: <Clock3 className="h-5 w-5 text-violet-600" />
    }
  ];

  return (
    <div className="admin-page space-y-5">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">搜索投影</h1>
          <p className="admin-page-subtitle">监控 PostgreSQL 到 Elasticsearch / LightRAG 的版本差异、积压与失败恢复</p>
        </div>
        <div className="admin-page-actions">
          <Button variant="outline" onClick={() => void runBatch("reconcile")}>
            <Search className="mr-2 h-4 w-4" />对账
          </Button>
          <Button onClick={() => void runBatch("rebuild")}>
            <RotateCcw className="mr-2 h-4 w-4" />按范围重建
          </Button>
          <Button variant="outline" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={`mr-2 h-4 w-4 ${loading ? "animate-spin" : ""}`} />刷新
          </Button>
        </div>
      </div>

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {cards.map((card) => (
          <div key={card.label} className="rounded-2xl border bg-card p-5 shadow-sm">
            <div className="flex items-center justify-between text-sm text-muted-foreground">
              <span>{card.label}</span>{card.icon}
            </div>
            <div className="mt-3 text-3xl font-semibold tracking-tight">{card.value}</div>
          </div>
        ))}
      </div>

      <div className="flex flex-wrap items-center gap-3 rounded-2xl border bg-card p-4">
        <Select value={target} onValueChange={(value) => { setTarget(value); setCurrent(1); }}>
          <SelectTrigger className="w-48"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">全部目标</SelectItem>
            <SelectItem value="ELASTICSEARCH">Elasticsearch</SelectItem>
            <SelectItem value="LIGHTRAG">LightRAG</SelectItem>
          </SelectContent>
        </Select>
        <Select value={status} onValueChange={(value) => { setStatus(value); setCurrent(1); }}>
          <SelectTrigger className="w-48"><SelectValue /></SelectTrigger>
          <SelectContent>
            <SelectItem value="ALL">全部状态</SelectItem>
            {["PENDING", "RUNNING", "WAIT_EXTERNAL", "RETRY_WAIT", "SUCCEEDED", "BLOCKED"].map((item) => (
              <SelectItem key={item} value={item}>{item}</SelectItem>
            ))}
          </SelectContent>
        </Select>
        <Input className="w-56" value={kbInput} onChange={(event) => setKbInput(event.target.value)} placeholder="知识库 ID" />
        <Input className="w-56" value={docInput} onChange={(event) => setDocInput(event.target.value)} placeholder="文档 ID" />
        <Button variant="outline" onClick={() => { setKbId(kbInput.trim()); setDocId(docInput.trim()); setCurrent(1); }}>
          查询
        </Button>
      </div>

      <div className="overflow-hidden rounded-2xl border bg-card shadow-sm">
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="border-b bg-muted/40 text-left text-xs uppercase tracking-wide text-muted-foreground">
              <tr>
                <th className="px-4 py-3">目标 / 动作</th>
                <th className="px-4 py-3">知识库 / 文档</th>
                <th className="px-4 py-3">期望 / 已应用</th>
                <th className="px-4 py-3">状态 / 阶段</th>
                <th className="px-4 py-3">期望时间</th>
                <th className="px-4 py-3">失败原因</th>
                <th className="px-4 py-3 text-right">操作</th>
              </tr>
            </thead>
            <tbody className="divide-y">
              {(page?.records || []).map((item) => (
                <tr key={item.id} className="align-top hover:bg-muted/20">
                  <td className="px-4 py-3"><div className="font-medium">{item.target}</div><div className="text-xs text-muted-foreground">{item.action}</div></td>
                  <td className="px-4 py-3 font-mono text-xs"><div>{item.kbId}</div><div className="mt-1 text-muted-foreground">{item.docId || "—"}</div></td>
                  <td className="px-4 py-3 font-mono">{item.desiredVersion} / {item.appliedVersion}</td>
                  <td className="px-4 py-3"><Badge variant="outline" className={statusClass[item.status]}>{item.status}</Badge><div className="mt-1 text-xs text-muted-foreground">{item.phase || "—"}</div></td>
                  <td className="whitespace-nowrap px-4 py-3 text-xs">{formatTime(item.desiredTime)}</td>
                  <td className="max-w-sm px-4 py-3 text-xs text-red-600"><div className="line-clamp-3" title={item.lastError || ""}>{item.lastError || "—"}</div></td>
                  <td className="px-4 py-3 text-right">
                    {item.status === "BLOCKED" || item.status === "RETRY_WAIT" ? (
                      <Button size="sm" variant="outline" onClick={() => void handleRetry(item)}>重试</Button>
                    ) : "—"}
                  </td>
                </tr>
              ))}
              {!loading && !(page?.records || []).length ? (
                <tr><td colSpan={7} className="px-4 py-12 text-center text-muted-foreground">暂无投影任务</td></tr>
              ) : null}
            </tbody>
          </table>
        </div>
        <div className="flex items-center justify-between border-t px-4 py-3 text-sm text-muted-foreground">
          <span>共 {page?.total ?? 0} 条</span>
          <div className="flex gap-2">
            <Button size="sm" variant="outline" disabled={current <= 1} onClick={() => setCurrent((value) => Math.max(1, value - 1))}>上一页</Button>
            <span className="px-2 py-2">{page?.current ?? current} / {Math.max(1, page?.pages ?? 1)}</span>
            <Button size="sm" variant="outline" disabled={current >= (page?.pages ?? 1)} onClick={() => setCurrent((value) => value + 1)}>下一页</Button>
          </div>
        </div>
      </div>

      <div className="overflow-hidden rounded-2xl border bg-card shadow-sm">
        <div className="flex items-center gap-3 border-b px-5 py-4">
          <ServerCog className="h-5 w-5 text-violet-600" />
          <div>
            <h2 className="font-semibold">LightRAG workspace</h2>
            <p className="text-xs text-muted-foreground">地址由服务端按知识库 ID 确定；配置指纹变化时必须重建</p>
          </div>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-sm">
            <thead className="border-b bg-muted/40 text-left text-xs uppercase tracking-wide text-muted-foreground">
              <tr><th className="px-4 py-3">知识库 / workspace</th><th className="px-4 py-3">状态</th><th className="px-4 py-3">版本 / 指纹</th><th className="px-4 py-3">最后校验</th><th className="px-4 py-3 text-right">操作</th></tr>
            </thead>
            <tbody className="divide-y">
              {workspaces.map((workspace) => (
                <tr key={workspace.id}>
                  <td className="px-4 py-3 font-mono text-xs"><div>{workspace.kbId}</div><div className="text-muted-foreground">{workspace.workspaceKey}</div></td>
                  <td className="px-4 py-3"><Badge variant="outline">{workspace.healthStatus}</Badge>{workspace.lastError ? <div className="mt-1 max-w-md text-xs text-red-600">{workspace.lastError}</div> : null}</td>
                  <td className="px-4 py-3 text-xs"><div>{workspace.lightragVersion || "—"} / {workspace.apiVersion || "—"}</div><div className="mt-1 max-w-48 truncate font-mono text-muted-foreground" title={workspace.configFingerprint || ""}>{workspace.configFingerprint || "—"}</div></td>
                  <td className="px-4 py-3 text-xs">{formatTime(workspace.lastHealthTime)}</td>
                  <td className="px-4 py-3 text-right"><div className="flex justify-end gap-2"><Button size="sm" variant="outline" onClick={() => void workspaceAction(workspace, "validate")}>校验</Button>{workspace.enabled === 1 ? <Button size="sm" variant="outline" onClick={() => void workspaceAction(workspace, "disable")}>关闭</Button> : <Button size="sm" onClick={() => void workspaceAction(workspace, "enable")}>启用并重建</Button>}</div></td>
                </tr>
              ))}
              {!workspaces.length ? <tr><td colSpan={5} className="px-4 py-10 text-center text-muted-foreground">暂无 workspace 绑定</td></tr> : null}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
