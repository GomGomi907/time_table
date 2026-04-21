"use client";

import { useEffect, useMemo, useState } from "react";
import { apiClient, normalizeApiList } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";

export interface TaskResourceItem {
  id: string;
  title: string;
  notes?: string | null;
  status?: string | null;
  due?: string | null;
  dueDate?: string | null;
  estimatedMinutes?: number | null;
  priority?: number | null;
  goalId?: string | null;
  goalTitle?: string | null;
  eventId?: string | null;
  scheduledStartAt?: string | null;
  scheduledEndAt?: string | null;
}

export const isTaskCompleted = (task: TaskResourceItem) =>
  ["completed", "done", "closed"].includes(task.status?.toLowerCase() ?? "");

export const getTaskDueAt = (task: TaskResourceItem) => task.dueDate ?? task.due ?? null;

export const formatTaskDueLabel = (task: TaskResourceItem) => {
  const dueAt = getTaskDueAt(task);

  if (!dueAt) {
    return "기한 미설정";
  }

  const parsed = new Date(dueAt);

  if (Number.isNaN(parsed.getTime())) {
    return "기한 확인 필요";
  }

  return new Intl.DateTimeFormat("ko-KR", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(parsed);
};

export const useTasksResource = () => {
  const [data, setData] = useState<TaskResourceItem[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = async () => {
    setIsLoading(true);

    try {
      const response = await apiClient.get<TaskResourceItem[]>("/tasks");
      setData(normalizeApiList<TaskResourceItem>(response.data));
      setError(null);
    } catch (requestError) {
      console.error("Failed to fetch tasks", requestError);
      setError(getApiErrorMessage(requestError, "할 일을 불러오지 못했습니다."));
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    void refresh();
  }, []);

  const summary = useMemo(() => {
    const pending = data.filter((task) => !isTaskCompleted(task));
    const completed = data.filter((task) => isTaskCompleted(task));
    const unassigned = pending.filter(
      (task) => !task.eventId && !task.scheduledStartAt
    );
    const dueSoon = pending.filter((task) => {
      const dueAt = getTaskDueAt(task);

      if (!dueAt) {
        return false;
      }

      const diff = new Date(dueAt).getTime() - Date.now();
      return diff > 0 && diff <= 1000 * 60 * 60 * 48;
    });

    return {
      pending,
      completed,
      unassigned,
      dueSoon,
    };
  }, [data]);

  return {
    data,
    summary,
    isLoading,
    error,
    refresh,
  };
};
