"use client";

import { create } from "zustand";
import { getCurrentWeekEventQuery } from "@/shared/api/compat";
import {
  apiEnvelopeClient,
  normalizeApiList,
  requestApiData,
  requestApiEnvelope,
} from "@/shared/api/client";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type {
  ApiMeta,
  ApiResult,
  CompleteEventRequest,
  CreateEventRequest,
  Event,
  EventQuery,
  ExtendEventRequest,
  PostponeEventRequest,
  StartEventRequest,
  UpdateEventRequest,
} from "@/shared/api/types";

interface EventsStore {
  items: Event[];
  query: EventQuery | null;
  meta: ApiMeta;
  isLoading: boolean;
  isMutating: boolean;
  hasLoaded: boolean;
  error: string | null;
  refresh: (query?: EventQuery) => Promise<Event[]>;
  createEvent: (payload: CreateEventRequest) => Promise<Event>;
  updateEvent: (id: string, payload: UpdateEventRequest) => Promise<Event>;
  deleteEvent: (id: string) => Promise<void>;
  startEvent: (id: string, payload: StartEventRequest) => Promise<Event>;
  completeEvent: (id: string, payload: CompleteEventRequest) => Promise<Event>;
  postponeEvent: (id: string, payload: PostponeEventRequest) => Promise<Event>;
  extendEvent: (id: string, payload: ExtendEventRequest) => Promise<Event>;
}

const EMPTY_META: ApiMeta = {};

export const useEventsStore = create<EventsStore>((set, get) => ({
  items: [],
  query: null,
  meta: EMPTY_META,
  isLoading: false,
  isMutating: false,
  hasLoaded: false,
  error: null,
  refresh: async (query) => {
    const effectiveQuery = query ?? get().query ?? getCurrentWeekEventQuery();

    set((state) => ({
      ...state,
      query: effectiveQuery,
      isLoading: true,
    }));

    try {
      const envelope = await requestApiEnvelope(
        apiEnvelopeClient.get<ApiResult<Event[]>>("/events", {
          params: effectiveQuery,
        })
      );

      const items = normalizeApiList<Event>(envelope.data);
      set((state) => ({
        ...state,
        items,
        meta: envelope.meta,
        query: effectiveQuery,
        isLoading: false,
        hasLoaded: true,
        error: null,
      }));

      return items;
    } catch (error) {
      console.error("Failed to fetch events", error);
      set((state) => ({
        ...state,
        query: effectiveQuery,
        isLoading: false,
        hasLoaded: true,
        error: getApiErrorMessage(error, "일정을 불러오지 못했습니다."),
      }));
      return get().items;
    }
  },
  createEvent: async (payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      const createdEvent = await requestApiData(
        apiEnvelopeClient.post<ApiResult<Event>>("/events", payload)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh(get().query ?? getCurrentWeekEventQuery());
      return createdEvent;
    } catch (error) {
      console.error("Failed to create event", error);
      const message = getApiErrorMessage(error, "일정을 저장하지 못했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  updateEvent: async (id, payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      const updatedEvent = await requestApiData(
        apiEnvelopeClient.patch<ApiResult<Event>>(`/events/${id}`, payload)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh(get().query ?? getCurrentWeekEventQuery());
      return updatedEvent;
    } catch (error) {
      console.error("Failed to update event", error);
      const message = getApiErrorMessage(error, "일정 수정에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  deleteEvent: async (id) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(apiEnvelopeClient.delete<ApiResult<null>>(`/events/${id}`));

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh(get().query ?? getCurrentWeekEventQuery());
    } catch (error) {
      console.error("Failed to delete event", error);
      const message = getApiErrorMessage(error, "일정 삭제에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  startEvent: async (id, payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      const updatedEvent = await requestApiData(
        apiEnvelopeClient.post<ApiResult<Event>>(`/events/${id}/start`, payload)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh(get().query ?? getCurrentWeekEventQuery());
      return updatedEvent;
    } catch (error) {
      console.error("Failed to start event", error);
      const message = getApiErrorMessage(error, "일정 시작 처리에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  completeEvent: async (id, payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      const updatedEvent = await requestApiData(
        apiEnvelopeClient.post<ApiResult<Event>>(`/events/${id}/complete`, payload)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh(get().query ?? getCurrentWeekEventQuery());
      return updatedEvent;
    } catch (error) {
      console.error("Failed to complete event", error);
      const message = getApiErrorMessage(error, "일정 완료 처리에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  postponeEvent: async (id, payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      const updatedEvent = await requestApiData(
        apiEnvelopeClient.post<ApiResult<Event>>(`/events/${id}/postpone`, payload)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh(get().query ?? getCurrentWeekEventQuery());
      return updatedEvent;
    } catch (error) {
      console.error("Failed to postpone event", error);
      const message = getApiErrorMessage(error, "일정 미루기에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  extendEvent: async (id, payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      const updatedEvent = await requestApiData(
        apiEnvelopeClient.post<ApiResult<Event>>(`/events/${id}/extend`, payload)
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh(get().query ?? getCurrentWeekEventQuery());
      return updatedEvent;
    } catch (error) {
      console.error("Failed to extend event", error);
      const message = getApiErrorMessage(error, "일정 연장 처리에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
}));
