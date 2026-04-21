"use client";

import { create } from "zustand";
import { toGoal } from "@/shared/api/compat";
import {
  apiEnvelopeClient,
  normalizeApiList,
  requestApiData,
  requestApiEnvelope,
} from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type {
  ApiMeta,
  ApiResult,
  CreateGoalRequest,
  Goal,
  GoalProgressUpdateRequest,
  UpdateGoalRequest,
} from "@/shared/api/types";

interface GoalsStore {
  items: Goal[];
  meta: ApiMeta;
  isLoading: boolean;
  isMutating: boolean;
  hasLoaded: boolean;
  error: string | null;
  refresh: () => Promise<Goal[]>;
  createGoal: (payload: CreateGoalRequest) => Promise<Goal>;
  updateGoal: (id: string, payload: UpdateGoalRequest) => Promise<Goal>;
  deleteGoal: (id: string) => Promise<void>;
  updateProgress: (id: string, payload: GoalProgressUpdateRequest) => Promise<Goal>;
}

const EMPTY_META: ApiMeta = {};

export const useGoalsStore = create<GoalsStore>((set, get) => ({
  items: [],
  meta: EMPTY_META,
  isLoading: false,
  isMutating: false,
  hasLoaded: false,
  error: null,
  refresh: async () => {
    set((state) => (state.isLoading ? state : { ...state, isLoading: true }));

    try {
      const envelope = await requestApiEnvelope(
        apiEnvelopeClient.get<ApiResult<Goal[]>>("/goals")
      );
      const items = normalizeApiList<Goal>(envelope.data).map(toGoal);

      set({
        items,
        meta: envelope.meta,
        isLoading: false,
        isMutating: false,
        hasLoaded: true,
        error: null,
      });

      return items;
    } catch (error) {
      console.error("Failed to fetch goals", error);
      set((state) => ({
        ...state,
        isLoading: false,
        hasLoaded: true,
        error: getApiErrorMessage(error, "목표를 불러오지 못했습니다."),
      }));
      return get().items;
    }
  },
  createGoal: async (payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      const createdGoal = toGoal(
        await requestApiData(
          apiEnvelopeClient.post<ApiResult<Goal>>("/goals", payload)
        )
      );

      set((state) => ({
        ...state,
        items: [...state.items, createdGoal],
        isMutating: false,
        error: null,
      }));

      return createdGoal;
    } catch (error) {
      console.error("Failed to create goal", error);
      const message = getApiErrorMessage(error, "목표를 저장하지 못했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  updateGoal: async (id, payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      const updatedGoal = toGoal(
        await requestApiData(
          apiEnvelopeClient.patch<ApiResult<Goal>>(`/goals/${id}`, payload)
        )
      );

      set((state) => ({
        ...state,
        items: state.items.map((goal) => (goal.id === id ? updatedGoal : goal)),
        isMutating: false,
        error: null,
      }));

      return updatedGoal;
    } catch (error) {
      console.error("Failed to update goal", error);
      const message = getApiErrorMessage(error, "목표 수정에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  deleteGoal: async (id) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(apiEnvelopeClient.delete<ApiResult<null>>(`/goals/${id}`));

      set((state) => ({
        ...state,
        items: state.items.filter((goal) => goal.id !== id),
        isMutating: false,
        error: null,
      }));
    } catch (error) {
      console.error("Failed to delete goal", error);
      const message = getApiErrorMessage(error, "목표 삭제에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  updateProgress: async (id, payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      const updatedGoal = toGoal(
        await requestApiData(
          apiEnvelopeClient.post<ApiResult<Goal>>(`/goals/${id}/progress`, payload)
        )
      );

      set((state) => ({
        ...state,
        items: state.items.map((goal) => (goal.id === id ? updatedGoal : goal)),
        isMutating: false,
        error: null,
      }));

      return updatedGoal;
    } catch (error) {
      console.error("Failed to update goal progress", error);
      const message = getApiErrorMessage(error, "목표 진행률 갱신에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
}));
