"use client";

import { create } from "zustand";
import { apiEnvelopeClient, requestApiData } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type { ApiResult, SettingsResponse, SettingsUpdateRequest } from "@/shared/api/types";

interface SettingsStore {
  data: SettingsResponse | null;
  isLoading: boolean;
  isSaving: boolean;
  hasLoaded: boolean;
  error: string | null;
  refresh: () => Promise<SettingsResponse | null>;
  save: (payload: SettingsUpdateRequest) => Promise<SettingsResponse>;
}

export const useSettingsStore = create<SettingsStore>((set) => ({
  data: null,
  isLoading: false,
  isSaving: false,
  hasLoaded: false,
  error: null,
  refresh: async () => {
    set((state) => (state.isLoading ? state : { ...state, isLoading: true }));

    try {
      const data = await requestApiData(
        apiEnvelopeClient.get<ApiResult<SettingsResponse>>("/settings")
      );

      set({
        data,
        isLoading: false,
        isSaving: false,
        hasLoaded: true,
        error: null,
      });

      return data;
    } catch (error) {
      console.error("Failed to fetch settings", error);
      set((state) => ({
        ...state,
        isLoading: false,
        hasLoaded: true,
        error: getApiErrorMessage(error, "설정을 불러오지 못했습니다."),
      }));
      return null;
    }
  },
  save: async (payload) => {
    set((state) => ({ ...state, isSaving: true }));

    try {
      const data = await requestApiData(
        apiEnvelopeClient.put<ApiResult<SettingsResponse>>("/settings", payload)
      );

      set((state) => ({
        ...state,
        data,
        isSaving: false,
        error: null,
      }));

      return data;
    } catch (error) {
      console.error("Failed to update settings", error);
      const message = getApiErrorMessage(error, "설정을 저장하지 못했습니다.");
      set((state) => ({
        ...state,
        isSaving: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
}));
