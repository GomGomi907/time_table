"use client";

import { create } from "zustand";
import { toTask } from "@/shared/api/compat";
import { apiEnvelopeClient, requestApiData } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type {
  ApiResult,
  CompleteFocusItemRequest,
  ConfirmOverrunRequest,
  FocusCurrentResponse,
  FocusRecommendationsResponse,
  PostponeFocusItemRequest,
  StartRecommendedTaskRequest,
} from "@/shared/api/types";

interface FocusStore {
  current: FocusCurrentResponse | null;
  recommendations: FocusRecommendationsResponse | null;
  isLoading: boolean;
  isMutating: boolean;
  hasLoaded: boolean;
  error: string | null;
  refresh: () => Promise<FocusCurrentResponse | null>;
  refreshRecommendations: () => Promise<FocusRecommendationsResponse | null>;
  startRecommendedTask: (payload: StartRecommendedTaskRequest) => Promise<void>;
  completeCurrent: (payload: CompleteFocusItemRequest) => Promise<void>;
  postponeCurrent: (payload: PostponeFocusItemRequest) => Promise<void>;
  confirmOverrun: (payload: ConfirmOverrunRequest) => Promise<void>;
}

const normalizeFocusCurrent = (data: FocusCurrentResponse): FocusCurrentResponse => ({
  ...data,
  recommendedTasks: data.recommendedTasks.map(toTask),
});

const normalizeRecommendations = (
  data: FocusRecommendationsResponse
): FocusRecommendationsResponse => ({
  ...data,
  recommendedTasks: data.recommendedTasks.map(toTask),
});

export const useFocusStore = create<FocusStore>((set, get) => ({
  current: null,
  recommendations: null,
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
      const data = normalizeFocusCurrent(
        await requestApiData(
          apiEnvelopeClient.get<ApiResult<FocusCurrentResponse>>("/focus/current")
        )
      );

      set((state) => ({
        ...state,
        current: data,
        isLoading: false,
        hasLoaded: true,
        error: null,
      }));

      return data;
    } catch (error) {
      console.error("Failed to fetch focus current", error);
      set((state) => ({
        ...state,
        isLoading: false,
        hasLoaded: true,
        error: getApiErrorMessage(error, "현재 집중 상태를 불러오지 못했습니다."),
      }));
      return get().current;
    }
  },
  refreshRecommendations: async () => {
    try {
      const data = normalizeRecommendations(
        await requestApiData(
          apiEnvelopeClient.get<ApiResult<FocusRecommendationsResponse>>(
            "/focus/recommendations"
          )
        )
      );

      set((state) => ({
        ...state,
        recommendations: data,
        error: null,
      }));

      return data;
    } catch (error) {
      console.error("Failed to fetch focus recommendations", error);
      set((state) => ({
        ...state,
        error: getApiErrorMessage(error, "집중 추천을 불러오지 못했습니다."),
      }));
      return get().recommendations;
    }
  },
  startRecommendedTask: async (payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(
        apiEnvelopeClient.post<ApiResult<null>>(
          "/focus/current/start-recommended-task",
          payload
        )
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await Promise.all([get().refresh(), get().refreshRecommendations()]);
    } catch (error) {
      console.error("Failed to start recommended task", error);
      const message = getApiErrorMessage(error, "추천 태스크 시작에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  completeCurrent: async (payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(
        apiEnvelopeClient.post<ApiResult<null>>("/focus/current/complete", payload)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await Promise.all([get().refresh(), get().refreshRecommendations()]);
    } catch (error) {
      console.error("Failed to complete current focus item", error);
      const message = getApiErrorMessage(error, "현재 집중 대상 완료 처리에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  postponeCurrent: async (payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(
        apiEnvelopeClient.post<ApiResult<null>>("/focus/current/postpone", payload)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await Promise.all([get().refresh(), get().refreshRecommendations()]);
    } catch (error) {
      console.error("Failed to postpone current focus item", error);
      const message = getApiErrorMessage(error, "현재 집중 대상 미루기에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  confirmOverrun: async (payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(
        apiEnvelopeClient.post<ApiResult<null>>("/focus/current/confirm-overrun", payload)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await Promise.all([get().refresh(), get().refreshRecommendations()]);
    } catch (error) {
      console.error("Failed to confirm focus overrun", error);
      const message = getApiErrorMessage(error, "집중 시간 초과 확인 처리에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
}));
