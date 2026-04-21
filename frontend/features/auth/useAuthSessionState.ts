"use client";

import { useEffect } from "react";
import type { AuthSessionResponse, GoogleStartResponse } from "@/shared/api/types";
import { useAuthSessionStore } from "./storeAuthSession";

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
  const session = useAuthSessionStore((state) => state.session);
  const googleStart = useAuthSessionStore((state) => state.googleStart);
  const isLoading = useAuthSessionStore((state) => state.isLoading);
  const hasLoaded = useAuthSessionStore((state) => state.hasLoaded);
  const error = useAuthSessionStore((state) => state.error);
  const refresh = useAuthSessionStore((state) => state.refresh);
  const startGoogleLoginRequest = useAuthSessionStore((state) => state.startGoogleLogin);
  const logoutRequest = useAuthSessionStore((state) => state.logout);

  useEffect(() => {
    if (!hasLoaded && !isLoading) {
      void refresh();
    }
  }, [hasLoaded, isLoading, refresh]);

  const startGoogleLogin = async () => {
    const url = await startGoogleLoginRequest();
    window.location.assign(url);
  };

  const logout = async () => {
    await logoutRequest();
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
