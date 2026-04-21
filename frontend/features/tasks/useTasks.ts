"use client";

import { useEffect } from "react";
import type { TaskQuery } from "@/shared/api/types";
import { useTasksStore } from "./storeTasks";

const EMPTY_QUERY: TaskQuery = {};

export const useTasks = (query?: TaskQuery) => {
  const data = useTasksStore((state) => state.items);
  const activeQuery = useTasksStore((state) => state.query);
  const meta = useTasksStore((state) => state.meta);
  const isLoading = useTasksStore((state) => state.isLoading);
  const isMutating = useTasksStore((state) => state.isMutating);
  const hasLoaded = useTasksStore((state) => state.hasLoaded);
  const error = useTasksStore((state) => state.error);
  const refresh = useTasksStore((state) => state.refresh);
  const createTask = useTasksStore((state) => state.createTask);
  const updateTask = useTasksStore((state) => state.updateTask);
  const deleteTask = useTasksStore((state) => state.deleteTask);
  const completeTask = useTasksStore((state) => state.completeTask);
  const scheduleTask = useTasksStore((state) => state.scheduleTask);

  const normalizedQuery = query ?? EMPTY_QUERY;

  useEffect(() => {
    const queryChanged =
      activeQuery.status !== normalizedQuery.status ||
      activeQuery.dueBefore !== normalizedQuery.dueBefore ||
      activeQuery.goalId !== normalizedQuery.goalId ||
      activeQuery.unassigned !== normalizedQuery.unassigned;

    if (!hasLoaded || queryChanged) {
      void refresh(normalizedQuery);
    }
  }, [activeQuery, hasLoaded, normalizedQuery, refresh]);

  return {
    data,
    query: activeQuery,
    meta,
    isLoading,
    isMutating,
    error,
    refresh: () => refresh(normalizedQuery),
    createTask,
    updateTask,
    deleteTask,
    completeTask,
    scheduleTask,
  };
};
