"use client";

import { useEffect } from "react";
import { useRescheduleSuggestionsStore } from "./storeRescheduleSuggestions";

export const useRescheduleSuggestions = () => {
  const data = useRescheduleSuggestionsStore((state) => state.items);
  const isLoading = useRescheduleSuggestionsStore((state) => state.isLoading);
  const isMutating = useRescheduleSuggestionsStore((state) => state.isMutating);
  const hasLoaded = useRescheduleSuggestionsStore((state) => state.hasLoaded);
  const error = useRescheduleSuggestionsStore((state) => state.error);
  const refresh = useRescheduleSuggestionsStore((state) => state.refresh);
  const requestReschedule = useRescheduleSuggestionsStore((state) => state.requestReschedule);
  const applySuggestion = useRescheduleSuggestionsStore((state) => state.applySuggestion);
  const rejectSuggestion = useRescheduleSuggestionsStore((state) => state.rejectSuggestion);
  const revertSuggestion = useRescheduleSuggestionsStore((state) => state.revertSuggestion);

  useEffect(() => {
    if (!hasLoaded) {
      void refresh();
    }
  }, [hasLoaded, refresh]);

  return {
    data,
    isLoading,
    isMutating,
    error,
    refresh,
    requestReschedule,
    applySuggestion,
    rejectSuggestion,
    revertSuggestion,
  };
};
