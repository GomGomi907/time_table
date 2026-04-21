"use client";

import { create } from "zustand";
import { apiEnvelopeClient, requestApiData } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type {
  ApiResult,
  RescheduleRequest,
  RescheduleSuggestion,
} from "@/shared/api/types";

interface RescheduleSuggestionsStore {
  items: RescheduleSuggestion[];
  isLoading: boolean;
  isMutating: boolean;
  hasLoaded: boolean;
  error: string | null;
  refresh: () => Promise<RescheduleSuggestion[]>;
  requestReschedule: (payload: RescheduleRequest) => Promise<void>;
  applySuggestion: (id: string) => Promise<void>;
  rejectSuggestion: (id: string) => Promise<void>;
  revertSuggestion: (id: string) => Promise<void>;
}

export const useRescheduleSuggestionsStore = create<RescheduleSuggestionsStore>((set, get) => ({
  items: [],
  isLoading: false,
  isMutating: false,
  hasLoaded: false,
  error: null,
  refresh: async () => {
    set((state) => ({
      ...state,
      isLoading: true,
    }));

    try {
      const data = await requestApiData(
        apiEnvelopeClient.get<ApiResult<RescheduleSuggestion[]>>("/agent/suggestions")
      );

      set((state) => ({
        ...state,
        items: data ?? [],
        isLoading: false,
        hasLoaded: true,
        error: null,
      }));

      return data ?? [];
    } catch (error) {
      console.error("Failed to fetch reschedule suggestions", error);
      set((state) => ({
        ...state,
        isLoading: false,
        hasLoaded: true,
        error: getApiErrorMessage(error, "재조율 제안을 불러오지 못했습니다."),
      }));
      return get().items;
    }
  },
  requestReschedule: async (payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(
        apiEnvelopeClient.post<ApiResult<null>>("/agent/reschedule", payload)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh();
    } catch (error) {
      console.error("Failed to request reschedule", error);
      const message = getApiErrorMessage(error, "재조율 요청에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  applySuggestion: async (id) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(
        apiEnvelopeClient.post<ApiResult<null>>(`/agent/suggestions/${id}/apply`)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh();
    } catch (error) {
      console.error("Failed to apply suggestion", error);
      const message = getApiErrorMessage(error, "제안 반영에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  rejectSuggestion: async (id) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(
        apiEnvelopeClient.post<ApiResult<null>>(`/agent/suggestions/${id}/reject`)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh();
    } catch (error) {
      console.error("Failed to reject suggestion", error);
      const message = getApiErrorMessage(error, "제안 거절에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  revertSuggestion: async (id) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(
        apiEnvelopeClient.post<ApiResult<null>>(`/agent/suggestions/${id}/revert`)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh();
    } catch (error) {
      console.error("Failed to revert suggestion", error);
      const message = getApiErrorMessage(error, "제안 되돌리기에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
}));
