"use client";

import { useEffect, useState } from "react";
import { apiClient } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type {
  CompleteFocusItemRequest,
  ConfirmOverrunRequest,
  FocusCurrentResponse,
  FocusState,
  PostponeFocusItemRequest,
  StartRecommendedTaskRequest,
  Task,
} from "@/shared/api/types";

const normalizeRecommendedTasks = (tasks: unknown[]): Task[] =>
  tasks.map((task) => {
    const item = (task ?? {}) as Record<string, unknown>;
    return {
      id: String(item.id ?? ""),
      title: String(item.title ?? "추천 태스크"),
      description: null,
      dueDate: typeof item.dueDate === "string" ? item.dueDate : null,
      estimatedMinutes:
        typeof item.estimatedMinutes === "number" ? item.estimatedMinutes : null,
      actualMinutes: null,
      priority:
        item.priority === 1 ||
        item.priority === 2 ||
        item.priority === 3 ||
        item.priority === 4 ||
        item.priority === 5
          ? item.priority
          : 3,
      goalId: null,
      eventId: null,
      status: "pending",
      sourceType: "local",
      externalProvider: null,
      externalSourceId: null,
      createdAt: null,
      updatedAt: null,
    };
  });

const normalizeFocusState = (value: unknown): FocusState =>
  typeof value === "string" ? (value as FocusState) : "NO_ACTIVE_ITEM";

const normalizeFocusResponse = (payload: Record<string, unknown>): FocusCurrentResponse => ({
  focusState: normalizeFocusState(payload.focusState),
  currentItem: (payload.currentItem as FocusCurrentResponse["currentItem"]) ?? null,
  nextItem: (payload.nextItem as FocusCurrentResponse["nextItem"]) ?? null,
  recommendedTasks: Array.isArray(payload.recommendedTasks)
    ? normalizeRecommendedTasks(payload.recommendedTasks)
    : [],
  activeSuggestion: (payload.activeSuggestion as FocusCurrentResponse["activeSuggestion"]) ?? null,
  remainingMinutes:
    typeof payload.remainingMinutes === "number" ? payload.remainingMinutes : 0,
});

export const useFocusResource = () => {
  const [data, setData] = useState<FocusCurrentResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isMutating, setIsMutating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = async () => {
    setIsLoading(true);
    try {
      const response = await apiClient.get<Record<string, unknown>>("/focus/current");
      setData(normalizeFocusResponse(response.data));
      setError(null);
    } catch (requestError) {
      console.error("Failed to fetch focus resource", requestError);
      setError(getApiErrorMessage(requestError, "집중 화면 정보를 불러오지 못했습니다."));
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    void refresh();
    const timer = window.setInterval(() => {
      void refresh();
    }, 30000);
    return () => window.clearInterval(timer);
  }, []);

  const wrapMutation = async (request: Promise<{ data: Record<string, unknown> }>) => {
    setIsMutating(true);
    try {
      const response = await request;
      setData(normalizeFocusResponse(response.data));
      setError(null);
      return response.data;
    } catch (requestError) {
      setError(getApiErrorMessage(requestError, "집중 상태 변경에 실패했습니다."));
      throw requestError;
    } finally {
      setIsMutating(false);
    }
  };

  const startRecommendedTask = async (payload: StartRecommendedTaskRequest) =>
    wrapMutation(
      apiClient.post<Record<string, unknown>>("/focus/current/start-recommended-task", payload)
    );

  const completeCurrent = async (payload: CompleteFocusItemRequest) =>
    wrapMutation(apiClient.post<Record<string, unknown>>("/focus/current/complete", payload));

  const postponeCurrent = async (payload: PostponeFocusItemRequest) =>
    wrapMutation(apiClient.post<Record<string, unknown>>("/focus/current/postpone", payload));

  const confirmOverrun = async (payload: ConfirmOverrunRequest) =>
    wrapMutation(apiClient.post<Record<string, unknown>>("/focus/current/confirm-overrun", payload));

  return {
    data,
    isLoading,
    isMutating,
    error,
    refresh,
    startRecommendedTask,
    completeCurrent,
    postponeCurrent,
    confirmOverrun,
  };
};
