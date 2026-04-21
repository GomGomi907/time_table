"use client";

import { useEffect, useMemo } from "react";
import { getCurrentWeekEventQuery } from "@/shared/api/compat";
import type { EventQuery } from "@/shared/api/types";
import { useEventsStore } from "./storeEvents";

export const useEvents = (query?: EventQuery) => {
  const data = useEventsStore((state) => state.items);
  const activeQuery = useEventsStore((state) => state.query);
  const meta = useEventsStore((state) => state.meta);
  const isLoading = useEventsStore((state) => state.isLoading);
  const isMutating = useEventsStore((state) => state.isMutating);
  const hasLoaded = useEventsStore((state) => state.hasLoaded);
  const error = useEventsStore((state) => state.error);
  const refresh = useEventsStore((state) => state.refresh);
  const createEvent = useEventsStore((state) => state.createEvent);
  const updateEvent = useEventsStore((state) => state.updateEvent);
  const deleteEvent = useEventsStore((state) => state.deleteEvent);
  const startEvent = useEventsStore((state) => state.startEvent);
  const completeEvent = useEventsStore((state) => state.completeEvent);
  const postponeEvent = useEventsStore((state) => state.postponeEvent);
  const extendEvent = useEventsStore((state) => state.extendEvent);

  const fallbackQuery = useMemo(() => getCurrentWeekEventQuery(), []);
  const normalizedQuery = query ?? fallbackQuery;

  useEffect(() => {
    const queryChanged =
      activeQuery?.from !== normalizedQuery.from || activeQuery?.to !== normalizedQuery.to;

    if (!hasLoaded || queryChanged) {
      void refresh(normalizedQuery);
    }
  }, [activeQuery?.from, activeQuery?.to, hasLoaded, normalizedQuery, refresh]);

  return {
    data,
    query: activeQuery ?? normalizedQuery,
    meta,
    isLoading,
    isMutating,
    error,
    refresh: () => refresh(normalizedQuery),
    createEvent,
    updateEvent,
    deleteEvent,
    startEvent,
    completeEvent,
    postponeEvent,
    extendEvent,
  };
};
