"use client";

import { useEffect, useState } from "react";
import { apiClient } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type { AuthSessionResponse, GoogleStartResponse } from "@/shared/api/types";

export interface AuthSessionState {
  session: AuthSessionResponse | null;
  googleStart: GoogleStartResponse | null;
  isLoading: boolean;
  error: string | null;
  refresh: () => Promise<void>;
  startGoogleLogin: () => Promise<void>;
  logout: () => Promise<void>;
}

export const useAuthSessionState = (): AuthSessionState => {
  const [session, setSession] = useState<AuthSessionResponse | null>(null);
  const [googleStart, setGoogleStart] = useState<GoogleStartResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const refresh = async () => {
    setIsLoading(true);
    try {
      const [{ data: sessionData }, { data: googleStartData }] = await Promise.all([
        apiClient.get<AuthSessionResponse>("/auth/session"),
        apiClient.get<GoogleStartResponse>("/auth/google/start"),
      ]);
      setSession(sessionData);
      setGoogleStart(googleStartData);
      setError(null);
    } catch (requestError) {
      console.error("Failed to load auth session state", requestError);
      setSession(null);
      setGoogleStart(null);
      setError(getApiErrorMessage(requestError, "로그인 상태를 확인하지 못했습니다. 잠시 후 다시 시도해 주세요."));
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    void refresh();
  }, []);

  const startGoogleLogin = async () => {
    const { data } = await apiClient.get<GoogleStartResponse>("/auth/google/start");
    setGoogleStart(data);

    if (!data.enabled || !data.url) {
      throw new Error(data.message ?? "Google 로그인을 지금 사용할 수 없습니다.");
    }

    window.location.assign(data.url);
  };

  const logout = async () => {
    await apiClient.post("/auth/logout");
    window.location.assign("/login");
  };

  return {
    session,
    googleStart,
    isLoading,
    error,
    refresh,
    startGoogleLogin,
    logout,
  };
};
