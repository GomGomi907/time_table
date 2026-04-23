"use client";

import { useEffect } from "react";

import { api } from "@/lib/api";
import { OnboardingStatus } from "@/lib/types";
import { useAppStore } from "@/stores/app-store";

export function useOnboardingBootstrap() {
  const session = useAppStore((state) => state.session);
  const sessionPhase = useAppStore((state) => state.sessionPhase);
  const onboardingPhase = useAppStore((state) => state.onboardingPhase);
  const onboardingStatus = useAppStore((state) => state.onboardingStatus);
  const onboardingError = useAppStore((state) => state.onboardingError);
  const setOnboardingPhase = useAppStore((state) => state.setOnboardingPhase);
  const setOnboardingStatus = useAppStore((state) => state.setOnboardingStatus);
  const setOnboardingError = useAppStore((state) => state.setOnboardingError);
  const clearOnboarding = useAppStore((state) => state.clearOnboarding);

  async function refreshOnboarding() {
    if (!session?.authenticated) {
      clearOnboarding();
      return;
    }

    try {
      setOnboardingPhase("loading");
      const nextStatus = await api.getOnboardingStatus();
      setOnboardingStatus(nextStatus);
    } catch (error) {
      setOnboardingError(error instanceof Error ? error.message : "온보딩 상태를 불러오지 못했습니다.");
    }
  }

  function applyOnboardingStatus(status: OnboardingStatus) {
    setOnboardingStatus(status);
  }

  useEffect(() => {
    if (!session?.authenticated) {
      clearOnboarding();
      return;
    }

    if (sessionPhase !== "ready" || onboardingPhase !== "idle") {
      return;
    }

    void refreshOnboarding();
  }, [session?.authenticated, sessionPhase, onboardingPhase, clearOnboarding]);

  const onboardingCompleted = onboardingStatus?.completed ?? false;

  return {
    onboardingPhase,
    onboardingStatus,
    onboardingError,
    onboardingCompleted,
    needsOnboarding:
      Boolean(session?.authenticated) &&
      onboardingPhase === "ready" &&
      !onboardingCompleted,
    refreshOnboarding,
    applyOnboardingStatus,
  };
}
