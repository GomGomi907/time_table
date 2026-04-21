"use client";

import { useEffect, useState } from "react";
import { apiClient } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type { GoalCreateRequest, GoalResponse } from "@/shared/api/types";

export const useGoals = () => {
  const [data, setData] = useState<GoalResponse[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isMutating, setIsMutating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = async () => {
    setIsLoading(true);
    try {
      const response = await apiClient.get<GoalResponse[]>("/goals");
      setData(response.data);
      setError(null);
    } catch (requestError) {
      console.error("Failed to fetch goals", requestError);
      setError(getApiErrorMessage(requestError, "목표를 불러오지 못했습니다."));
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    void refresh();
  }, []);

  const createGoal = async (payload: GoalCreateRequest) => {
    setIsMutating(true);
    try {
      const response = await apiClient.post<GoalResponse>("/goals", payload);
      setData((current) => [...current, response.data]);
      setError(null);
      return response.data;
    } catch (requestError) {
      console.error("Failed to create goal", requestError);
      setError(getApiErrorMessage(requestError, "목표를 저장하지 못했습니다."));
      throw requestError;
    } finally {
      setIsMutating(false);
    }
  };

  const updateGoal = async (id: string, payload: GoalCreateRequest) => {
    setIsMutating(true);
    try {
      const response = await apiClient.put<GoalResponse>(`/goals/${id}`, payload);
      setData((current) => current.map((g) => (g.id === id ? response.data : g)));
      setError(null);
      return response.data;
    } catch (requestError) {
      console.error("Failed to update goal", requestError);
      setError(getApiErrorMessage(requestError, "목표 수정에 실패했습니다."));
      throw requestError;
    } finally {
      setIsMutating(false);
    }
  };

  const deleteGoal = async (id: string) => {
    setIsMutating(true);
    try {
      await apiClient.delete(`/goals/${id}`);
      setData((current) => current.filter((g) => g.id !== id));
      setError(null);
    } catch (requestError) {
      console.error("Failed to delete goal", requestError);
      setError(getApiErrorMessage(requestError, "목표 삭제에 실패했습니다."));
      throw requestError;
    } finally {
      setIsMutating(false);
    }
  };

  return {
    data,
    isLoading,
    isMutating,
    error,
    refresh,
    createGoal,
    updateGoal,
    deleteGoal,
  };
};
