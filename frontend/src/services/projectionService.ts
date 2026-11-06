import { api } from "@/services/api";
import type { PageResult } from "@/services/knowledgeService";

export type ProjectionTarget = "ELASTICSEARCH" | "LIGHTRAG";
export type ProjectionStatus =
  | "PENDING"
  | "RUNNING"
  | "WAIT_EXTERNAL"
  | "RETRY_WAIT"
  | "SUCCEEDED"
  | "BLOCKED";

export interface ProjectionState {
  id: string;
  target: ProjectionTarget;
  kbId: string;
  docId?: string | null;
  desiredVersion: number;
  appliedVersion: number;
  action: "UPSERT" | "DELETE" | "PURGE_KB";
  status: ProjectionStatus;
  phase?: string | null;
  attemptCount: number;
  desiredTime?: string | null;
  completedAt?: string | null;
  nextRetryTime?: string | null;
  lastError?: string | null;
}

export interface ProjectionSummary {
  total: number;
  counts: Record<string, number>;
  esSloBreaches: number;
  graphSloBreaches: number;
  generatedAt: string;
}

export interface ProjectionFilters {
  current?: number;
  size?: number;
  target?: string;
  status?: string;
  kbId?: string;
  docId?: string;
}

export interface ProjectionBatchRequest {
  target?: ProjectionTarget;
  kbId?: string;
  docId?: string;
}

export interface GraphWorkspace {
  id: string;
  kbId: string;
  workspaceKey: string;
  enabled: number;
  healthStatus: string;
  lightragVersion?: string | null;
  apiVersion?: string | null;
  configFingerprint?: string | null;
  lastHealthTime?: string | null;
  lastError?: string | null;
}

export const getProjectionSummary = () =>
  api.get<ProjectionSummary, ProjectionSummary>("/admin/projections/summary");

export const getProjections = (params: ProjectionFilters) =>
  api.get<PageResult<ProjectionState>, PageResult<ProjectionState>>("/admin/projections", { params });

export const retryProjection = (id: string) =>
  api.post<void, void>(`/admin/projections/${encodeURIComponent(id)}/retry`);

export const rebuildProjections = (payload: ProjectionBatchRequest = {}) =>
  api.post<{ batchId: string }, { batchId: string }>("/admin/projections/rebuild", payload);

export const reconcileProjections = (payload: ProjectionBatchRequest = {}) =>
  api.post<{ batchId: string }, { batchId: string }>("/admin/projections/reconcile", payload);

export const getGraphWorkspaces = () =>
  api.get<GraphWorkspace[], GraphWorkspace[]>("/admin/kg/workspaces");

export const validateGraphWorkspace = (kbId: string) =>
  api.post<GraphWorkspace, GraphWorkspace>(`/admin/kg/workspaces/${encodeURIComponent(kbId)}/validate`);

export const enableGraphWorkspace = (kbId: string) =>
  api.post<GraphWorkspace, GraphWorkspace>(`/admin/kg/workspaces/${encodeURIComponent(kbId)}/enable`);

export const disableGraphWorkspace = (kbId: string) =>
  api.post<void, void>(`/admin/kg/workspaces/${encodeURIComponent(kbId)}/disable`);
