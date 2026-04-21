"use client";

import { create } from "zustand";
import { apiEnvelopeClient, requestApiData } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type { ApiResult, PriorityProposal } from "@/shared/api/types";

interface PriorityProposalsStore {
  items: PriorityProposal[];
  isLoading: boolean;
  isMutating: boolean;
  hasLoaded: boolean;
  error: string | null;
  refresh: () => Promise<PriorityProposal[]>;
  acceptProposal: (id: string) => Promise<void>;
  rejectProposal: (id: string) => Promise<void>;
}

export const usePriorityProposalsStore = create<PriorityProposalsStore>((set, get) => ({
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
        apiEnvelopeClient.get<ApiResult<PriorityProposal[]>>("/priority/proposals")
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
      console.error("Failed to fetch priority proposals", error);
      set((state) => ({
        ...state,
        isLoading: false,
        hasLoaded: true,
        error: getApiErrorMessage(error, "우선순위 제안을 불러오지 못했습니다."),
      }));
      return get().items;
    }
  },
  acceptProposal: async (id) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(
        apiEnvelopeClient.post<ApiResult<null>>(`/priority/proposals/${id}/accept`)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh();
    } catch (error) {
      console.error("Failed to accept priority proposal", error);
      const message = getApiErrorMessage(error, "우선순위 제안 승인에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  rejectProposal: async (id) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(
        apiEnvelopeClient.post<ApiResult<null>>(`/priority/proposals/${id}/reject`)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh();
    } catch (error) {
      console.error("Failed to reject priority proposal", error);
      const message = getApiErrorMessage(error, "우선순위 제안 거절에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
}));
