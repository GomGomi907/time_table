"use client";

import { create } from "zustand";
import { apiEnvelopeClient, requestApiData } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type {
  ApiResult,
  ResolveSyncConflictRequest,
  SyncCalendarRequest,
  SyncStatus,
  SyncTasksRequest,
} from "@/shared/api/types";

interface SyncStatusStore {
  data: SyncStatus | null;
  isLoading: boolean;
  isMutating: boolean;
  hasLoaded: boolean;
  error: string | null;
  refresh: () => Promise<SyncStatus | null>;
  syncCalendar: (payload: SyncCalendarRequest) => Promise<SyncStatus | null>;
  syncTasks: (payload: SyncTasksRequest) => Promise<SyncStatus | null>;
  resolveConflict: (id: string, payload: ResolveSyncConflictRequest) => Promise<void>;
}

export const useSyncStatusStore = create<SyncStatusStore>((set, get) => ({
  data: null,
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
        apiEnvelopeClient.get<ApiResult<SyncStatus>>("/sync/status")
      );

      set((state) => ({
        ...state,
        data,
        isLoading: false,
        hasLoaded: true,
        error: null,
      }));

      return data;
    } catch (error) {
      console.error("Failed to fetch sync status", error);
      set((state) => ({
        ...state,
        isLoading: false,
        hasLoaded: true,
        error: getApiErrorMessage(error, "동기화 상태를 불러오지 못했습니다."),
      }));
      return get().data;
    }
  },
  syncCalendar: async (payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(
        apiEnvelopeClient.post<ApiResult<null>>("/sync/google/calendar", payload)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      return await get().refresh();
    } catch (error) {
      console.error("Failed to run calendar sync", error);
      const message = getApiErrorMessage(error, "캘린더 동기화 실행에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  syncTasks: async (payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(
        apiEnvelopeClient.post<ApiResult<null>>("/sync/google/tasks", payload)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      return await get().refresh();
    } catch (error) {
      console.error("Failed to run tasks sync", error);
      const message = getApiErrorMessage(error, "할 일 동기화 실행에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  resolveConflict: async (id, payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(
        apiEnvelopeClient.post<ApiResult<null>>(`/sync/conflicts/${id}/resolve`, payload)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh();
    } catch (error) {
      console.error("Failed to resolve sync conflict", error);
      const message = getApiErrorMessage(error, "동기화 충돌 해결에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
}));
