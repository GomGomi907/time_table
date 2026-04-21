"use client";

import { useMemo } from "react";
import {
  getCurrentWeekEventQuery,
  scheduleBlockToCreateEventRequest,
  toWeekScheduleResponse,
} from "@/shared/api/compat";
import { apiEnvelopeClient, requestApiData } from "@/shared/api/client";
import type {
  ApiResult,
  ChatCommandResponse,
  ScheduleImportRequest,
  ScheduleBlockWriteRequest,
} from "@/shared/api/types";
import { useEvents } from "@/features/events/useEvents";

export const useWeekSchedule = () => {
  const events = useEvents(getCurrentWeekEventQuery());

  const data = useMemo(
    () => toWeekScheduleResponse(events.data),
    [events.data]
  );

  const importSchedule = async (request: ScheduleImportRequest) => {
    const mode = request.replaceExisting
      ? "기존 이번 주 일정을 대체하고"
      : "기존 일정은 유지한 채로";
    const message = `${mode} 다음 텍스트를 이번 주 일정으로 반영해줘.\n${request.rawText}`;

    await requestApiData(
      apiEnvelopeClient.post<ApiResult<ChatCommandResponse>>("/chat/command", {
        message,
      })
    );

    return toWeekScheduleResponse(await events.refresh());
  };

  const createBlock = async (payload: ScheduleBlockWriteRequest) => {
    await events.createEvent(scheduleBlockToCreateEventRequest(payload));
  };

  const updateBlock = async (blockId: string, payload: ScheduleBlockWriteRequest) => {
    await events.updateEvent(blockId, scheduleBlockToCreateEventRequest(payload));
  };

  const deleteBlock = async (blockId: string) => {
    await events.deleteEvent(blockId);
  };

  return {
    data,
    isLoading: events.isLoading,
    isMutating: events.isMutating,
    error: events.error,
    refresh: async () => {
      await events.refresh();
    },
    importSchedule,
    createBlock,
    updateBlock,
    deleteBlock,
  };
};
