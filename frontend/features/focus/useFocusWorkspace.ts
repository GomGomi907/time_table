"use client";

import { useEffect, useMemo, useState } from "react";
import { useTasksResource } from "@/components/widgets/useTasksResource";
import { useGoals } from "@/features/goals/useGoals";
import { useWeekSchedule } from "@/features/schedule/useWeekSchedule";
import { apiClient } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import {
  buildDerivedFocusData,
  type FocusWorkspaceData,
} from "./focus-utils";

interface ApiFocusTask {
  id: string;
  title: string;
  estimatedMinutes?: number | null;
  dueAt?: string | null;
}

interface ApiFocusGoal {
  id: string;
  title: string;
}

interface ApiFocusItem {
  type?: "event" | "task";
  id: string;
  title: string;
  startAt?: string | null;
  endAt?: string | null;
  remainingMinutes?: number;
  priority?: number | null;
  note?: string | null;
  goal?: ApiFocusGoal | null;
  relatedTasks?: ApiFocusTask[];
  actualStartAt?: string | null;
  actualEndAt?: string | null;
  isDelayed?: boolean;
}

interface ApiFocusSuggestion {
  id?: string;
  summary?: string;
  title?: string;
  detail?: string;
}

interface ApiFocusCurrentResponse {
  focusState?: FocusWorkspaceData["focusState"];
  currentItem?: ApiFocusItem | null;
  nextItem?: ApiFocusItem | null;
  recommendedTasks?: ApiFocusTask[];
  activeSuggestion?: ApiFocusSuggestion | null;
  remainingMinutes?: number;
}

const normalizeApiItem = (item: ApiFocusItem | null | undefined) => {
  if (!item) {
    return null;
  }

  const startTimeLabel = item.startAt
    ? new Date(item.startAt).toLocaleTimeString("ko-KR", {
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      })
    : null;
  const endTimeLabel = item.endAt
    ? new Date(item.endAt).toLocaleTimeString("ko-KR", {
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      })
    : null;

  return {
    type: item.type ?? "event",
    id: item.id,
    title: item.title,
    startAt: item.startAt ?? null,
    endAt: item.endAt ?? null,
    startTimeLabel,
    endTimeLabel,
    remainingMinutes: item.remainingMinutes ?? 0,
    priority: item.priority ?? null,
    note: item.note ?? null,
    goal: item.goal
      ? {
          id: item.goal.id,
          title: item.goal.title,
        }
      : null,
    relatedTasks:
      item.relatedTasks?.map((task) => ({
        id: task.id,
        title: task.title,
        estimatedMinutes: task.estimatedMinutes ?? null,
        dueAt: task.dueAt ?? null,
      })) ?? [],
    actualStartAt: item.actualStartAt ?? null,
    actualEndAt: item.actualEndAt ?? null,
    isDelayed: item.isDelayed ?? false,
  };
};

const normalizeApiResponse = (
  payload: ApiFocusCurrentResponse
): FocusWorkspaceData => ({
  focusState: payload.focusState ?? "EMPTY",
  currentItem: normalizeApiItem(payload.currentItem),
  nextItem: normalizeApiItem(payload.nextItem),
  recommendedTasks:
    payload.recommendedTasks?.map((task) => ({
      id: task.id,
      title: task.title,
      estimatedMinutes: task.estimatedMinutes ?? null,
      dueAt: task.dueAt ?? null,
    })) ?? [],
  activeSuggestion: payload.activeSuggestion
    ? {
        id: payload.activeSuggestion.id ?? "api-suggestion",
        title:
          payload.activeSuggestion.title ??
          payload.activeSuggestion.summary ??
          "AI 제안",
        detail:
          payload.activeSuggestion.detail ??
          payload.activeSuggestion.summary ??
          "상세 설명이 없습니다.",
      }
    : null,
  remainingMinutes: payload.remainingMinutes ?? 0,
  overlappingItems: [],
});

export const useFocusWorkspace = () => {
  const schedule = useWeekSchedule();
  const goals = useGoals();
  const tasks = useTasksResource();
  const [now, setNow] = useState(() => new Date());
  const [apiData, setApiData] = useState<FocusWorkspaceData | null>(null);
  const [isApiLoading, setIsApiLoading] = useState(true);
  const [apiAvailable, setApiAvailable] = useState(false);
  const [statusMessage, setStatusMessage] = useState<string | null>(null);
  const [isMutating, setIsMutating] = useState(false);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 30000);
    return () => window.clearInterval(timer);
  }, []);

  const fetchFocusApi = async () => {
    setIsApiLoading(true);

    try {
      const response = await apiClient.get<ApiFocusCurrentResponse>("/focus/current");
      setApiData(normalizeApiResponse(response.data));
      setApiAvailable(true);
      setStatusMessage(null);
    } catch (requestError) {
      console.error("Failed to fetch focus current state", requestError);
      setApiData(null);
      setApiAvailable(false);
      setStatusMessage(
        "포커스 전용 API가 아직 준비되지 않아 시간표 기준 파생 모드로 표시합니다."
      );
    } finally {
      setIsApiLoading(false);
    }
  };

  useEffect(() => {
    void fetchFocusApi();
  }, []);

  const derivedData = useMemo(
    () =>
      buildDerivedFocusData({
        schedule: schedule.data,
        goals: goals.data,
        tasks: tasks.data,
        now,
      }),
    [goals.data, now, schedule.data, tasks.data]
  );

  const data = apiAvailable && apiData ? apiData : derivedData;

  const refresh = async () => {
    await Promise.all([
      schedule.refresh(),
      goals.refresh(),
      tasks.refresh(),
      fetchFocusApi(),
    ]);
  };

  const guardApiAction = async <T,>(
    callback: () => Promise<T>,
    fallbackMessage: string
  ) => {
    if (!apiAvailable) {
      setStatusMessage(fallbackMessage);
      return null;
    }

    setIsMutating(true);

    try {
      const result = await callback();
      setStatusMessage("실행 기록이 반영되었습니다.");
      await fetchFocusApi();
      return result;
    } catch (requestError) {
      setStatusMessage(
        getApiErrorMessage(requestError, "실행 기록을 저장하지 못했습니다.")
      );
      return null;
    } finally {
      setIsMutating(false);
    }
  };

  const recordStart = async () =>
    guardApiAction(
      () =>
        apiClient.post(
          `/events/${data.currentItem?.id}/start`,
          {
            startedAt: new Date().toISOString(),
            triggerSource: "manual",
          }
        ),
      "포커스 API 연결 후 실제 시작 시각을 저장할 수 있습니다."
    );

  const completeCurrent = async (payload: {
    completionType: string;
    memo?: string;
  }) =>
    guardApiAction(
      () =>
        apiClient.post("/focus/current/complete", {
          itemType: data.currentItem?.type ?? "event",
          itemId: data.currentItem?.id,
          completedAt: new Date().toISOString(),
          completionType: payload.completionType,
          memo: payload.memo ?? null,
        }),
      "포커스 API 연결 후 완료 기록을 저장할 수 있습니다."
    );

  const postponeCurrent = async (payload: {
    reason: string;
    requestAiReschedule: boolean;
  }) =>
    guardApiAction(
      () =>
        apiClient.post("/focus/current/postpone", {
          itemType: data.currentItem?.type ?? "event",
          itemId: data.currentItem?.id,
          reason: payload.reason,
          requestAiReschedule: payload.requestAiReschedule,
        }),
      "포커스 API 연결 후 미루기 기록을 저장할 수 있습니다."
    );

  const extendCurrent = async (extendMinutes: number) =>
    guardApiAction(
      () =>
        apiClient.post(`/events/${data.currentItem?.id}/extend`, {
          extendMinutes,
          reason: "focus_workspace",
        }),
      "포커스 API 연결 후 연장 요청을 저장할 수 있습니다."
    );

  const startRecommendedTask = async (taskId: string) =>
    guardApiAction(
      () =>
        apiClient.post("/focus/current/start-recommended-task", {
          taskId,
        }),
      "포커스 API 연결 후 추천 태스크 시작을 저장할 수 있습니다."
    );

  return {
    now,
    data,
    apiAvailable,
    isLoading:
      isApiLoading || schedule.isLoading || goals.isLoading || tasks.isLoading,
    isMutating,
    statusMessage,
    refresh,
    recordStart,
    completeCurrent,
    postponeCurrent,
    extendCurrent,
    startRecommendedTask,
  };
};
