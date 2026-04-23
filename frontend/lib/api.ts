"use client";

import {
  AuthSession,
  CreateGoalRequest,
  CreateScheduleBlockRequest,
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
  SettingsResponse,
  SettingsUpdateRequest,
  SyncStatusEnvelope,
  SyncStatusMeta,
  SyncStatusResponse,
  Task,
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

const API_BASE_URL =
  process.env.NEXT_PUBLIC_API_BASE_URL?.replace(/\/$/, "") ??
  "http://localhost:8080";

function buildUrl(path: string, query?: Record<string, string | boolean | undefined>) {
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

function readCookie(name: string) {
  if (typeof document === "undefined") {
    return null;
  }

  const cookie = document.cookie
    .split("; ")
    .find((entry) => entry.startsWith(`${name}=`));

  return cookie ? decodeURIComponent(cookie.split("=")[1] ?? "") : null;
}

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
  if (status === 401) {
    return "로그인이 필요합니다. 로그인 화면에서 세션을 시작해 주세요.";
  }

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

  return `요청에 실패했습니다. (${status})`;
}

async function ensureCsrfToken() {
  const existing = readCookie("XSRF-TOKEN");
  if (existing) {
    return existing;
  }

  await fetch(buildUrl("/api/auth/session"), {
    credentials: "include",
    cache: "no-store",
  });

  return readCookie("XSRF-TOKEN");
}

async function requestRaw<T>(path: string, init?: RequestInit, query?: Record<string, string | boolean | undefined>) {
  const method = init?.method?.toUpperCase() ?? "GET";
  const headers = new Headers(init?.headers);

  if (method !== "GET" && method !== "HEAD" && method !== "OPTIONS") {
    const csrfToken = await ensureCsrfToken();
    if (csrfToken) {
      headers.set("X-XSRF-TOKEN", csrfToken);
    }
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
    throw new Error(getErrorMessage(payload, response.status));
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

  getWeekSchedule() {
    return requestRaw<WeekScheduleResponse>("/api/schedule/week");
  },

  createScheduleBlock(request: CreateScheduleBlockRequest) {
    return requestRaw("/api/schedule/blocks", {
      method: "POST",
      body: JSON.stringify(request),
    });
  },

  updateScheduleBlock(blockId: string, request: CreateScheduleBlockRequest) {
    return requestRaw(`/api/schedule/blocks/${blockId}`, {
      method: "PUT",
      body: JSON.stringify(request),
    });
  },

  deleteScheduleBlock(blockId: string) {
    return requestRaw(`/api/schedule/blocks/${blockId}`, {
      method: "DELETE",
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

  getFocusCurrent() {
    return requestEnvelope<FocusCurrentView>("/api/focus/current");
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
      return this.deleteTask(itemId);
    }

    if (itemType.toLowerCase() === "event") {
      return this.deleteEvent(itemId);
    }

    throw new Error("삭제할 수 없는 포커스 항목입니다.");
  },

  getSuggestions() {
    return requestEnvelope<RescheduleSuggestion[]>("/api/agent/suggestions");
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
        reason: reason ?? "프론트에서 보류",
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
