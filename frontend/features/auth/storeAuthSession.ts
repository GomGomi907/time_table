"use client";

import { create } from "zustand";
import { apiEnvelopeClient, requestApiData } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type { ApiResult, AuthSessionResponse, GoogleStartResponse } from "@/shared/api/types";

interface AuthSessionStore {
  session: AuthSessionResponse | null;
  googleStart: GoogleStartResponse | null;
  isLoading: boolean;
  hasLoaded: boolean;
  error: string | null;
  refresh: () => Promise<void>;
  startGoogleLogin: () => Promise<string>;
  logout: () => Promise<void>;
}

export const useAuthSessionStore = create<AuthSessionStore>((set) => ({
  session: null,
  googleStart: null,
  isLoading: false,
  hasLoaded: false,
  error: null,
  refresh: async () => {
    set((state) => (state.isLoading ? state : { ...state, isLoading: true }));

    try {
      const [session, googleStart] = await Promise.all([
        requestApiData(
          apiEnvelopeClient.get<ApiResult<AuthSessionResponse>>("/auth/session")
        ),
        requestApiData(
          apiEnvelopeClient.get<ApiResult<GoogleStartResponse>>("/auth/google/start")
        ),
      ]);

      set({
        session,
        googleStart,
        isLoading: false,
        hasLoaded: true,
        error: null,
      });
    } catch (error) {
      console.error("Failed to load auth session state", error);
      set({
        session: null,
        googleStart: null,
        isLoading: false,
        hasLoaded: true,
        error: getApiErrorMessage(
          error,
          "로그인 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."
        ),
      });
    }
  },
  startGoogleLogin: async () => {
    try {
      const googleStart = await requestApiData(
        apiEnvelopeClient.get<ApiResult<GoogleStartResponse>>("/auth/google/start")
      );

      set((state) => ({
        ...state,
        googleStart,
        error: null,
      }));

      if (!googleStart.enabled || !googleStart.url) {
        throw new Error(googleStart.message ?? "Google 로그인을 지금 사용할 수 없습니다.");
      }

      return googleStart.url;
    } catch (error) {
      const message = getApiErrorMessage(error, "Google 로그인을 시작하지 못했습니다.");
      set((state) => ({
        ...state,
        error: message,
      }));
      throw new Error(message);
    }
  },
  logout: async () => {
    try {
      await requestApiData(apiEnvelopeClient.post<ApiResult<null>>("/auth/logout"));
    } finally {
      set({
        session: null,
        googleStart: null,
        isLoading: false,
        hasLoaded: false,
        error: null,
      });
    }
  },
}));
