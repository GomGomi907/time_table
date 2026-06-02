"use client";

import { AuthSession, OnboardingStatus } from "@/lib/types";
import { create } from "zustand";

export type SessionPhase = "idle" | "loading" | "ready" | "error";
export type NoticeTone = "info" | "success" | "error";

interface NoticeState {
  tone: NoticeTone;
  title: string;
  detail?: string;
}

interface AppState {
  sessionPhase: SessionPhase;
  session: AuthSession | null;
  sessionError: string | null;
  onboardingPhase: SessionPhase;
  onboardingStatus: OnboardingStatus | null;
  onboardingError: string | null;
  notice: NoticeState | null;
  bodyScrollLockIds: string[];
  bodyScrollLockCount: number;
  setSessionPhase: (phase: SessionPhase) => void;
  setSession: (session: AuthSession | null) => void;
  setSessionError: (message: string | null) => void;
  setOnboardingPhase: (phase: SessionPhase) => void;
  setOnboardingStatus: (status: OnboardingStatus | null) => void;
  setOnboardingError: (message: string | null) => void;
  clearOnboarding: () => void;
  clearSession: () => void;
  showNotice: (notice: NoticeState) => void;
  clearNotice: () => void;
  acquireBodyScrollLock: (id: string) => void;
  releaseBodyScrollLock: (id: string) => void;
}

export const useAppStore = create<AppState>((set) => ({
  sessionPhase: "idle",
  session: null,
  sessionError: null,
  onboardingPhase: "idle",
  onboardingStatus: null,
  onboardingError: null,
  notice: null,
  bodyScrollLockIds: [],
  bodyScrollLockCount: 0,
  setSessionPhase: (sessionPhase) => set({ sessionPhase }),
  setSession: (session) => set({ session, sessionPhase: "ready", sessionError: null }),
  setSessionError: (sessionError) => set({ sessionError, sessionPhase: "error" }),
  setOnboardingPhase: (onboardingPhase) => set({ onboardingPhase }),
  setOnboardingStatus: (onboardingStatus) =>
    set({ onboardingStatus, onboardingPhase: "ready", onboardingError: null }),
  setOnboardingError: (onboardingError) => set({ onboardingError, onboardingPhase: "error" }),
  clearOnboarding: () =>
    set({
      onboardingPhase: "idle",
      onboardingStatus: null,
      onboardingError: null,
    }),
  clearSession: () =>
    set({
      session: null,
      sessionPhase: "idle",
      sessionError: null,
      onboardingPhase: "idle",
      onboardingStatus: null,
      onboardingError: null,
    }),
  showNotice: (notice) => set({ notice }),
  clearNotice: () => set({ notice: null }),
  acquireBodyScrollLock: (id) =>
    set((state) => {
      if (state.bodyScrollLockIds.includes(id)) {
        return state;
      }
      const bodyScrollLockIds = [...state.bodyScrollLockIds, id];
      return { bodyScrollLockIds, bodyScrollLockCount: bodyScrollLockIds.length };
    }),
  releaseBodyScrollLock: (id) =>
    set((state) => {
      const bodyScrollLockIds = state.bodyScrollLockIds.filter((lockId) => lockId !== id);
      return { bodyScrollLockIds, bodyScrollLockCount: bodyScrollLockIds.length };
    }),
}));
