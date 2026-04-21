"use client";

import { useEffect, useState } from "react";
import { apiClient } from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type {
  ScheduleImportRequest,
  ScheduleBlockResponse,
  ScheduleBlockWriteRequest,
  WeekScheduleResponse,
} from "@/shared/api/types";

export const useWeekSchedule = () => {
  const [data, setData] = useState<WeekScheduleResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [isMutating, setIsMutating] = useState(false);

  const refresh = async () => {
    setIsLoading(true);
    try {
      const response = await apiClient.get<WeekScheduleResponse>("/schedule/week");
      setData(response.data);
      setError(null);
    } catch (requestError) {
      console.error("Failed to fetch weekly schedule", requestError);
      setError(getApiErrorMessage(requestError, "주간 시간표를 불러오지 못했습니다."));
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    void refresh();
  }, []);

  const importSchedule = async (request: ScheduleImportRequest) => {
    setIsMutating(true);
    try {
      const response = await apiClient.post<WeekScheduleResponse>("/schedule/import", {
        rawText: request.rawText,
        replaceExisting: request.replaceExisting,
      });
      setData(response.data);
      setError(null);
      return response.data;
    } catch (requestError) {
      console.error("Failed to import weekly schedule", requestError);
      setError(getApiErrorMessage(requestError, "일정을 자동으로 정리하지 못했습니다."));
      throw requestError;
    } finally {
      setIsMutating(false);
    }
  };

  const createBlock = async (payload: ScheduleBlockWriteRequest) => {
    setIsMutating(true);
    try {
      await apiClient.post<ScheduleBlockResponse>("/schedule/blocks", payload);
      await refresh();
    } catch (requestError) {
      console.error("Failed to create schedule block", requestError);
      setError(getApiErrorMessage(requestError, "일정 블록을 추가하지 못했습니다."));
      throw requestError;
    } finally {
      setIsMutating(false);
    }
  };

  const updateBlock = async (blockId: string, payload: ScheduleBlockWriteRequest) => {
    setIsMutating(true);
    try {
      await apiClient.put<ScheduleBlockResponse>(`/schedule/blocks/${blockId}`, payload);
      await refresh();
    } catch (requestError) {
      console.error("Failed to update schedule block", requestError);
      setError(getApiErrorMessage(requestError, "일정 블록을 수정하지 못했습니다."));
      throw requestError;
    } finally {
      setIsMutating(false);
    }
  };

  const deleteBlock = async (blockId: string) => {
    setIsMutating(true);
    try {
      await apiClient.delete(`/schedule/blocks/${blockId}`);
      await refresh();
    } catch (requestError) {
      console.error("Failed to delete schedule block", requestError);
      setError(getApiErrorMessage(requestError, "일정 블록을 삭제하지 못했습니다."));
      throw requestError;
    } finally {
      setIsMutating(false);
    }
  };

  return {
    data,
    isLoading,
    isMutating,
    error,
    refresh,
    importSchedule,
    createBlock,
    updateBlock,
    deleteBlock,
  };
};
