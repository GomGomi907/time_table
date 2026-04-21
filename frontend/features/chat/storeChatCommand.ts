"use client";

import { create } from "zustand";
import { apiEnvelopeClient, requestApiData } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type {
  ApiResult,
  ChatCommandRequest,
  ChatCommandResponse,
} from "@/shared/api/types";

interface ChatCommandStore {
  lastResponse: ChatCommandResponse | null;
  history: ChatCommandResponse[];
  isSubmitting: boolean;
  error: string | null;
  submit: (payload: ChatCommandRequest | string) => Promise<ChatCommandResponse>;
  reset: () => void;
}

export const useChatCommandStore = create<ChatCommandStore>((set) => ({
  lastResponse: null,
  history: [],
  isSubmitting: false,
  error: null,
  submit: async (payload) => {
    const request = typeof payload === "string" ? { message: payload } : payload;

    set((state) => ({
      ...state,
      isSubmitting: true,
    }));

    try {
      const response = await requestApiData(
        apiEnvelopeClient.post<ApiResult<ChatCommandResponse>>("/chat/command", request)
      );

      set((state) => ({
        ...state,
        lastResponse: response,
        history: [response, ...state.history].slice(0, 20),
        isSubmitting: false,
        error: null,
      }));

      return response;
    } catch (error) {
      console.error("Failed to submit chat command", error);
      const message = getApiErrorMessage(error, "AI 명령 실행에 실패했습니다.");
      set((state) => ({
        ...state,
        isSubmitting: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  reset: () => {
    set({
      lastResponse: null,
      history: [],
      isSubmitting: false,
      error: null,
    });
  },
}));
