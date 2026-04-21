"use client";

import { useEffect, useMemo } from "react";
import {
  legacyGoalRequestToCreateGoalRequest,
  toGoalResponse,
} from "@/shared/api/compat";
import type {
  GoalCreateRequest,
  GoalProgressUpdateRequest,
  GoalResponse,
} from "@/shared/api/types";
import { useGoalsStore } from "./storeGoals";

export const useGoalsResource = () => {
  const data = useGoalsStore((state) => state.items);
  const meta = useGoalsStore((state) => state.meta);
  const isLoading = useGoalsStore((state) => state.isLoading);
  const isMutating = useGoalsStore((state) => state.isMutating);
  const hasLoaded = useGoalsStore((state) => state.hasLoaded);
  const error = useGoalsStore((state) => state.error);
  const refresh = useGoalsStore((state) => state.refresh);
  const createGoal = useGoalsStore((state) => state.createGoal);
  const updateGoal = useGoalsStore((state) => state.updateGoal);
  const deleteGoal = useGoalsStore((state) => state.deleteGoal);
  const updateProgress = useGoalsStore((state) => state.updateProgress);

  useEffect(() => {
    if (!hasLoaded) {
      void refresh();
    }
  }, [hasLoaded, refresh]);

  return {
    data,
    meta,
    isLoading,
    isMutating,
    error,
    refresh,
    createGoal,
    updateGoal,
    deleteGoal,
    updateProgress,
  };
};

export const useGoals = () => {
  const resource = useGoalsResource();

  const data = useMemo<GoalResponse[]>(
    () => resource.data.map(toGoalResponse),
    [resource.data]
  );

  return {
    data,
    isLoading: resource.isLoading,
    isMutating: resource.isMutating,
    error: resource.error,
    refresh: resource.refresh,
    createGoal: async (payload: GoalCreateRequest) =>
      toGoalResponse(
        await resource.createGoal(legacyGoalRequestToCreateGoalRequest(payload))
      ),
    updateGoal: async (id: string, payload: GoalCreateRequest) =>
      toGoalResponse(
        await resource.updateGoal(id, legacyGoalRequestToCreateGoalRequest(payload))
      ),
    deleteGoal: resource.deleteGoal,
    updateProgress: (id: string, payload: GoalProgressUpdateRequest) =>
      resource.updateProgress(id, payload),
  };
};
