"use client";

import { useEffect } from "react";

import { api } from "@/lib/api";
import { useAppStore } from "@/stores/app-store";

export function useSessionBootstrap() {
  const session = useAppStore((state) => state.session);
  const sessionPhase = useAppStore((state) => state.sessionPhase);
  const sessionError = useAppStore((state) => state.sessionError);
  const setSession = useAppStore((state) => state.setSession);
  const setSessionPhase = useAppStore((state) => state.setSessionPhase);
  const setSessionError = useAppStore((state) => state.setSessionError);

  async function refreshSession() {
    try {
      setSessionPhase("loading");
      const nextSession = await api.getSession();
      setSession(nextSession);
    } catch (error) {
      setSessionError(error instanceof Error ? error.message : "세션을 불러오지 못했습니다.");
    }
  }

  useEffect(() => {
    if (sessionPhase !== "idle") {
      return;
    }

    void refreshSession();
  }, [sessionPhase]);

  return {
    session,
    sessionPhase,
    sessionError,
    refreshSession,
  };
}
