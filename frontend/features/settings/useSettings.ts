"use client";

import { useEffect, useState } from "react";
import { apiClient } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type { SettingsResponse, SettingsUpdateRequest } from "@/shared/api/types";

export const useSettings = () => {
  const [data, setData] = useState<SettingsResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isSaving, setIsSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const refresh = async () => {
    setIsLoading(true);
    try {
      const response = await apiClient.get<SettingsResponse>("/settings");
      setData(response.data);
      setError(null);
    } catch (requestError) {
      console.error("Failed to fetch settings", requestError);
      setError(getApiErrorMessage(requestError, "설정을 불러오지 못했습니다."));
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    void refresh();
  }, []);

  const save = async (payload: SettingsUpdateRequest) => {
    setIsSaving(true);
    try {
      const response = await apiClient.put<SettingsResponse>("/settings", payload);
      setData(response.data);
      setError(null);
      return response.data;
    } catch (requestError) {
      console.error("Failed to update settings", requestError);
      setError(getApiErrorMessage(requestError, "설정을 저장하지 못했습니다."));
      throw requestError;
    } finally {
      setIsSaving(false);
    }
  };

  return {
    data,
    isLoading,
    isSaving,
    error,
    refresh,
    save,
  };
};
