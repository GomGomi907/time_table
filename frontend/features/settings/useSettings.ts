"use client";

import { useEffect } from "react";
import { useSettingsStore } from "./storeSettings";

export const useSettings = () => {
  const data = useSettingsStore((state) => state.data);
  const isLoading = useSettingsStore((state) => state.isLoading);
  const isSaving = useSettingsStore((state) => state.isSaving);
  const hasLoaded = useSettingsStore((state) => state.hasLoaded);
  const error = useSettingsStore((state) => state.error);
  const refresh = useSettingsStore((state) => state.refresh);
  const save = useSettingsStore((state) => state.save);

  useEffect(() => {
    if (!hasLoaded) {
      void refresh();
    }
  }, [hasLoaded, refresh]);

  return {
    data,
    isLoading,
    isSaving,
    error,
    refresh,
    save,
  };
};
