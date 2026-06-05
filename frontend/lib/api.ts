"use client";

import {
  AuthSession,
  CalendarRangeResponse,
  CsrfTokenResponse,
  CreateGoalRequest,
  CreateScheduleBlockRequest,
  EventItem,
  EventWriteRequest,
  DashboardSummaryResponse,
  FocusCurrentView,
  Goal,
  GoogleStartResponse,
  ManualSyncResponse,
  OnboardingAnswersRequest,
  OnboardingAnswersResponse,
  OnboardingBootstrapResponse,
  OnboardingCompletionRequest,
  OnboardingCompletionResponse,
  OnboardingStatus,
  RescheduleSuggestion,
  ScheduleBlock,
  ScheduleMutationPreflightResponse,
  SettingsResponse,
  SettingsUpdateRequest,
  SyncStatusEnvelope,
  SyncStatusMeta,
  SyncStatusResponse,
  Task,
  TaskWriteRequest,
  WeekScheduleResponse,
} from "@/lib/types";

interface ApiEnvelope<T> {
  success: boolean;
  data: T;
  meta?: Record<string, unknown>;
  error?: {
    code: string;
    message: string;
  };
}

export class ApiRequestError extends Error {
    constructor(
      message: string,
      public readonly status: number,
    ) {
      super(message);
      this.name = "ApiRequestError";
    }
}

export function isConflictError(error: unknown): error is ApiRequestError {
  return error instanceof ApiRequestError && error.status === 409;
}

function normalizeApiBaseUrl(value: string | undefined) {
  const normalized = (value ?? "http://localhost:8080").replace(/\/+$/, "");

  if (!normalized) {
    return "";
  }

  // All client calls in this module pass paths that already start with `/api`.
  // Accept legacy Docker/env values such as `http://localhost:8080/api` without
  // turning requests into `/api/api/...`.
  return normalized.replace(/\/api$/i, "");
}

const API_BASE_URL = normalizeApiBaseUrl(process.env.NEXT_PUBLIC_API_BASE_URL);

function buildUrl(path: string, query?: Record<string, string | boolean | undefined>) {
  if (!API_BASE_URL) {
    if (!query) {
      return path;
    }

    const searchParams = new URLSearchParams();
    for (const [key, value] of Object.entries(query)) {
      if (value === undefined) {
        continue;
      }
      searchParams.set(key, String(value));
    }
    const queryString = searchParams.toString();
    return queryString ? `${path}?${queryString}` : path;
  }

  const url = new URL(`${API_BASE_URL}${path}`);
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value === undefined) {
        continue;
      }
      url.searchParams.set(key, String(value));
    }
  }
  return url.toString();
}

let csrfTokenCache: CsrfTokenResponse | null = null;

async function parseResponse(response: Response) {
  if (response.status === 204) {
    return null;
  }

  const contentType = response.headers.get("content-type") ?? "";
  if (contentType.includes("application/json")) {
    return response.json();
  }

  const text = await response.text();
  return text || null;
}

function getErrorMessage(payload: unknown, status: number) {
  if (payload && typeof payload === "object") {
    const record = payload as Record<string, unknown>;
    const envelopeError =
      "error" in record && record.error && typeof record.error === "object"
        ? (record.error as Record<string, unknown>)
        : null;

    if (envelopeError?.message && typeof envelopeError.message === "string") {
      return envelopeError.message;
    }

    if (record.message && typeof record.message === "string") {
      return record.message;
    }
  }

  if (status === 401) {
    return "로그인이 필요합니다. 로그인 화면에서 세션을 시작할 수 있습니다.";
  }

  if (status === 403) {
    return "보안 확인에 실패했습니다. 잠시 후 다시 시도하거나 페이지를 새로고침하면 됩니다.";
  }

  if (status === 409) {
    return "다른 기기나 화면에서 데이터가 먼저 변경되었습니다. 최신 상태로 다시 불러옵니다.";
  }

  return `요청에 실패했습니다. (${status})`;
}

async function ensureCsrfToken(forceRefresh = false) {
  if (!forceRefresh && csrfTokenCache?.token) {
    return csrfTokenCache;
  }

  const response = await fetch(buildUrl("/api/auth/csrf"), {
    credentials: "include",
    cache: "no-store",
  });

  const payload = await parseResponse(response);
  if (!response.ok) {
    throw new ApiRequestError(getErrorMessage(payload, response.status), response.status);
  }

  csrfTokenCache = payload as CsrfTokenResponse;
  return csrfTokenCache;
}

async function requestRaw<T>(
  path: string,
  init?: RequestInit,
  query?: Record<string, string | boolean | undefined>,
  attempt = 0,
) {
  const method = init?.method?.toUpperCase() ?? "GET";
  const headers = new Headers(init?.headers);
  const needsCsrfToken = method !== "GET" && method !== "HEAD" && method !== "OPTIONS";

  if (needsCsrfToken) {
    const csrfToken = await ensureCsrfToken(attempt > 0);
    headers.set(csrfToken.headerName, csrfToken.token);
  }

  if (init?.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(buildUrl(path, query), {
    ...init,
    headers,
    credentials: "include",
    cache: "no-store",
  });

  const payload = await parseResponse(response);
  if (!response.ok) {
    if (response.status === 403 && needsCsrfToken && attempt === 0) {
      csrfTokenCache = null;
      return requestRaw<T>(path, init, query, 1);
    }
    throw new ApiRequestError(getErrorMessage(payload, response.status), response.status);
  }

  return payload as T;
}

async function requestEnvelope<T>(
  path: string,
  init?: RequestInit,
  query?: Record<string, string | boolean | undefined>,
) {
  const payload = await requestRaw<ApiEnvelope<T>>(path, init, query);
  if (!payload.success) {
    throw new Error(payload.error?.message ?? "응답 해석에 실패했습니다.");
  }

  return {
    data: payload.data,
    meta: (payload.meta ?? {}) as Record<string, unknown>,
  };
}

export const api = {
  getSession() {
    return requestRaw<AuthSession>("/api/auth/session");
  },

  getLoginStart() {
    return requestRaw<GoogleStartResponse>("/api/auth/google/start");
  },

  getDashboardSummary() {
    return requestEnvelope<DashboardSummaryResponse>("/api/dashboard/summary");
  },

  getOnboardingStatus() {
    return requestRaw<OnboardingStatus>("/api/onboarding/status");
  },

  bootstrapOnboarding() {
    return requestRaw<OnboardingBootstrapResponse>("/api/onboarding/bootstrap", {
      method: "POST",
    });
  },

  saveOnboardingAnswers(request: OnboardingAnswersRequest) {
    return requestRaw<OnboardingAnswersResponse>("/api/onboarding/answers", {
      method: "POST",
      body: JSON.stringify(request),
    });
  },

  completeOnboarding(request: OnboardingCompletionRequest) {
    return requestRaw<OnboardingCompletionResponse>("/api/onboarding/complete", {
      method: "POST",
      body: JSON.stringify(request),
    });
  },

  async logout() {
    await requestRaw("/api/auth/logout", {
      method: "POST",
    });
    csrfTokenCache = null;
  },

  getSettings() {
    return requestEnvelope<SettingsResponse>("/api/settings");
  },

  updateSettings(request: SettingsUpdateRequest) {
    return requestEnvelope<SettingsResponse>("/api/settings", {
      method: "PUT",
      body: JSON.stringify(request),
    });
  },

  getWeekSchedule(signal?: AbortSignal) {
    return requestRaw<WeekScheduleResponse>("/api/schedule/week", { signal });
  },

  getScheduleMutationPreflight(signal?: AbortSignal) {
    return requestRaw<ScheduleMutationPreflightResponse>("/api/schedule/conflicts/preflight", { signal });
  },

  getCalendarRange(
    request: {
      start: string;
      end: string;
      view?: "day" | "week" | "month" | "agenda";
      timezone?: string;
    },
    signal?: AbortSignal,
  ) {
    return requestEnvelope<CalendarRangeResponse>(
      "/api/calendar/range",
      { signal },
      {
        start: request.start,
        end: request.end,
        view: request.view ?? "week",
        timezone: request.timezone,
      },
    );
  },

  createScheduleBlock(request: CreateScheduleBlockRequest, signal?: AbortSignal) {
    return requestRaw<ScheduleBlock>("/api/schedule/blocks", {
      method: "POST",
      signal,
      body: JSON.stringify(request),
    });
  },

  updateScheduleBlock(blockId: string, request: CreateScheduleBlockRequest, signal?: AbortSignal) {
    return requestRaw<ScheduleBlock>(`/api/schedule/blocks/${blockId}`, {
      method: "PUT",
      signal,
      body: JSON.stringify(request),
    });
  },

  deleteScheduleBlock(blockId: string, signal?: AbortSignal) {
    return requestRaw<void>(`/api/schedule/blocks/${blockId}`, {
      method: "DELETE",
      signal,
    });
  },

  getGoals() {
    return requestEnvelope<Goal[]>("/api/goals");
  },

  createGoal(request: CreateGoalRequest) {
    return requestEnvelope<Goal>("/api/goals", {
      method: "POST",
      body: JSON.stringify(request),
    });
  },

  getTasks(unassigned = false) {
    return requestEnvelope<Task[]>("/api/tasks", undefined, {
      unassigned,
    });
  },

  getFocusCurrent(signal?: AbortSignal) {
    return requestEnvelope<FocusCurrentView>("/api/focus/current", { signal });
  },

  completeFocusItem(itemType: string, itemId: string) {
    return requestEnvelope<FocusCurrentView>("/api/focus/current/complete", {
      method: "POST",
      body: JSON.stringify({
        itemType,
        itemId,
        completedAt: new Date().toISOString(),
        completionType: "on_time",
      }),
    });
  },

  startRecommendedTask(taskId: string) {
    return requestEnvelope<FocusCurrentView>("/api/focus/current/start-recommended-task", {
      method: "POST",
      body: JSON.stringify({ taskId }),
    });
  },

  postponeFocusItem(itemType: string, itemId: string, reason: string) {
    return requestEnvelope<FocusCurrentView>("/api/focus/current/postpone", {
      method: "POST",
      body: JSON.stringify({
        itemType,
        itemId,
        reason,
        requestAiReschedule: true,
      }),
    });
  },

  completeScheduleBlock(blockId: string) {
    return requestEnvelope<FocusCurrentView>(`/api/focus/current/schedule-blocks/${blockId}/complete`, {
      method: "POST",
    });
  },

  postponeScheduleBlock(blockId: string, reason: string) {
    return requestEnvelope<FocusCurrentView>(`/api/focus/current/schedule-blocks/${blockId}/postpone`, {
      method: "POST",
      body: JSON.stringify({
        reason,
        requestAiReschedule: true,
      }),
    });
  },

  extendFocusItem(itemType: string, itemId: string, expectedExtraMinutes: number) {
    if (itemType.toLowerCase() !== "event") {
      throw new Error("현재 항목은 시간 연장을 지원하지 않습니다.");
    }

    return requestEnvelope<FocusCurrentView>("/api/focus/current/confirm-overrun", {
      method: "POST",
      body: JSON.stringify({
        itemType,
        itemId,
        action: "continue",
        expectedExtraMinutes,
      }),
    });
  },

  getEvent(eventId: string, signal?: AbortSignal) {
    return requestEnvelope<EventItem>(`/api/events/${eventId}`, { signal });
  },

  updateEvent(eventId: string, request: EventWriteRequest, signal?: AbortSignal) {
    return requestEnvelope<EventItem>(`/api/events/${eventId}`, {
      method: "PATCH",
      signal,
      body: JSON.stringify(request),
    });
  },

  getTask(taskId: string, signal?: AbortSignal) {
    return requestEnvelope<Task>(`/api/tasks/${taskId}`, { signal });
  },

  updateTask(taskId: string, request: TaskWriteRequest, signal?: AbortSignal) {
    return requestEnvelope<Task>(`/api/tasks/${taskId}`, {
      method: "PATCH",
      signal,
      body: JSON.stringify(request),
    });
  },

  deleteEvent(eventId: string) {
    return requestEnvelope<unknown>(`/api/events/${eventId}`, {
      method: "DELETE",
    });
  },

  deleteTask(taskId: string) {
    return requestEnvelope<unknown>(`/api/tasks/${taskId}`, {
      method: "DELETE",
    });
  },

  deleteFocusItem(itemType: string, itemId: string) {
    if (itemType.toLowerCase() === "task") {
      return api.deleteTask(itemId);
    }

    if (itemType.toLowerCase() === "event") {
      return api.deleteEvent(itemId);
    }

    throw new Error("삭제할 수 없는 포커스 항목입니다.");
  },

  getSuggestions(signal?: AbortSignal) {
    return requestEnvelope<RescheduleSuggestion[]>("/api/agent/suggestions", { signal });
  },

  applySuggestion(suggestionId: string, reason?: string) {
    return requestEnvelope<RescheduleSuggestion>(`/api/agent/suggestions/${suggestionId}/apply`, {
      method: "POST",
      body: JSON.stringify({
        reason: reason ?? "프론트에서 적용",
      }),
    });
  },

  rejectSuggestion(suggestionId: string, reason?: string) {
    return requestEnvelope<RescheduleSuggestion>(`/api/agent/suggestions/${suggestionId}/reject`, {
      method: "POST",
      body: JSON.stringify({
        reason: reason ?? "사용자가 이 제안을 사용하지 않음",
      }),
    });
  },

  requestManualReschedule(reason: string) {
    return requestEnvelope<RescheduleSuggestion>("/api/agent/reschedule", {
      method: "POST",
      body: JSON.stringify({
        triggerType: "manual_request",
        reason,
      }),
    });
  },

  async getSyncStatus() {
    const payload = await requestEnvelope<SyncStatusResponse>("/api/sync/status");
    return {
      data: payload.data,
      meta: payload.meta as SyncStatusMeta,
    } satisfies SyncStatusEnvelope;
  },

  requestGoogleCalendarSync() {
    return requestEnvelope<ManualSyncResponse>("/api/sync/google/calendar", {
      method: "POST",
    });
  },

  requestGoogleTasksSync() {
    return requestEnvelope<ManualSyncResponse>("/api/sync/google/tasks", {
      method: "POST",
    });
  },
};
