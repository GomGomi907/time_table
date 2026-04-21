"use client";

import { create } from "zustand";
import { toTask } from "@/shared/api/compat";
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
  CompleteTaskRequest,
  CreateTaskRequest,
  ScheduleTaskRequest,
  Task,
  TaskQuery,
  UpdateTaskRequest,
} from "@/shared/api/types";

interface TasksStore {
  items: Task[];
  query: TaskQuery;
  meta: ApiMeta;
  isLoading: boolean;
  isMutating: boolean;
  hasLoaded: boolean;
  error: string | null;
  refresh: (query?: TaskQuery) => Promise<Task[]>;
  createTask: (payload: CreateTaskRequest) => Promise<Task>;
  updateTask: (id: string, payload: UpdateTaskRequest) => Promise<Task>;
  deleteTask: (id: string) => Promise<void>;
  completeTask: (id: string, payload: CompleteTaskRequest) => Promise<Task>;
  scheduleTask: (id: string, payload: ScheduleTaskRequest) => Promise<Task>;
}

const EMPTY_META: ApiMeta = {};
const EMPTY_QUERY: TaskQuery = {};

export const useTasksStore = create<TasksStore>((set, get) => ({
  items: [],
  query: EMPTY_QUERY,
  meta: EMPTY_META,
  isLoading: false,
  isMutating: false,
  hasLoaded: false,
  error: null,
  refresh: async (query) => {
    const effectiveQuery = query ?? get().query;

    set((state) => ({
      ...state,
      query: effectiveQuery,
      isLoading: true,
    }));

    try {
      const envelope = await requestApiEnvelope(
        apiEnvelopeClient.get<ApiResult<Task[]>>("/tasks", {
          params: effectiveQuery,
        })
      );
      const items = normalizeApiList<Task>(envelope.data).map(toTask);

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
      console.error("Failed to fetch tasks", error);
      set((state) => ({
        ...state,
        query: effectiveQuery,
        isLoading: false,
        hasLoaded: true,
        error: getApiErrorMessage(error, "할 일을 불러오지 못했습니다."),
      }));
      return get().items;
    }
  },
  createTask: async (payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      const createdTask = toTask(
        await requestApiData(
          apiEnvelopeClient.post<ApiResult<Task>>("/tasks", payload)
        )
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh(get().query);
      return createdTask;
    } catch (error) {
      console.error("Failed to create task", error);
      const message = getApiErrorMessage(error, "할 일을 저장하지 못했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  updateTask: async (id, payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      const updatedTask = toTask(
        await requestApiData(
          apiEnvelopeClient.patch<ApiResult<Task>>(`/tasks/${id}`, payload)
        )
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh(get().query);
      return updatedTask;
    } catch (error) {
      console.error("Failed to update task", error);
      const message = getApiErrorMessage(error, "할 일 수정에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  deleteTask: async (id) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      await requestApiData(apiEnvelopeClient.delete<ApiResult<null>>(`/tasks/${id}`));

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh(get().query);
    } catch (error) {
      console.error("Failed to delete task", error);
      const message = getApiErrorMessage(error, "할 일 삭제에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  completeTask: async (id, payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      const completedTask = toTask(
        await requestApiData(
          apiEnvelopeClient.post<ApiResult<Task>>(`/tasks/${id}/complete`, payload)
        )
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh(get().query);
      return completedTask;
    } catch (error) {
      console.error("Failed to complete task", error);
      const message = getApiErrorMessage(error, "할 일 완료 처리에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
  scheduleTask: async (id, payload) => {
    set((state) => ({ ...state, isMutating: true }));

    try {
      const scheduledTask = toTask(
        await requestApiData(
          apiEnvelopeClient.post<ApiResult<Task>>(`/tasks/${id}/schedule`, payload)
        )
      );

      set((state) => ({
        ...state,
        isMutating: false,
        error: null,
      }));

      await get().refresh(get().query);
      return scheduledTask;
    } catch (error) {
      console.error("Failed to schedule task", error);
      const message = getApiErrorMessage(error, "할 일 일정 배치에 실패했습니다.");
      set((state) => ({
        ...state,
        isMutating: false,
        error: message,
      }));
      throw error instanceof Error ? error : new Error(message);
    }
  },
}));
