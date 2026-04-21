"use client";

import { useEffect } from "react";
import { useFocusStore } from "./storeFocus";

export const useFocusCurrent = () => {
  const data = useFocusStore((state) => state.current);
  const recommendations = useFocusStore((state) => state.recommendations);
  const isLoading = useFocusStore((state) => state.isLoading);
  const isMutating = useFocusStore((state) => state.isMutating);
  const hasLoaded = useFocusStore((state) => state.hasLoaded);
  const error = useFocusStore((state) => state.error);
  const refresh = useFocusStore((state) => state.refresh);
  const refreshRecommendations = useFocusStore((state) => state.refreshRecommendations);
  const startRecommendedTask = useFocusStore((state) => state.startRecommendedTask);
  const completeCurrent = useFocusStore((state) => state.completeCurrent);
  const postponeCurrent = useFocusStore((state) => state.postponeCurrent);
  const confirmOverrun = useFocusStore((state) => state.confirmOverrun);

  useEffect(() => {
    if (!hasLoaded) {
      void refresh();
      void refreshRecommendations();
    }
  }, [hasLoaded, refresh, refreshRecommendations]);

  return {
    data,
    recommendations,
    isLoading,
    isMutating,
    error,
    refresh,
    refreshRecommendations,
    startRecommendedTask,
    completeCurrent,
    postponeCurrent,
    confirmOverrun,
  };
};
