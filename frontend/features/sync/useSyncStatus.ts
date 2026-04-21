"use client";

import { useEffect } from "react";
import { useSyncStatusStore } from "./storeSyncStatus";

export const useSyncStatus = () => {
  const data = useSyncStatusStore((state) => state.data);
  const isLoading = useSyncStatusStore((state) => state.isLoading);
  const isMutating = useSyncStatusStore((state) => state.isMutating);
  const hasLoaded = useSyncStatusStore((state) => state.hasLoaded);
  const error = useSyncStatusStore((state) => state.error);
  const refresh = useSyncStatusStore((state) => state.refresh);
  const syncCalendar = useSyncStatusStore((state) => state.syncCalendar);
  const syncTasks = useSyncStatusStore((state) => state.syncTasks);
  const resolveConflict = useSyncStatusStore((state) => state.resolveConflict);

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
    syncCalendar,
    syncTasks,
    resolveConflict,
  };
};
