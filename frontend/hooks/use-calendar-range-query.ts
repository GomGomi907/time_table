"use client";

import { useQuery, useQueryClient } from "@tanstack/react-query";

import { api } from "@/lib/api";

export type CalendarRangeView = "day" | "week" | "month" | "agenda";
export const calendarRangeQueryRootKey = ["calendar", "range"] as const;

export interface CalendarRangeQueryInput {
  start: string;
  end: string;
  view: CalendarRangeView;
  timezone?: string;
}

export function calendarRangeQueryKey(input: CalendarRangeQueryInput) {
  return [
    ...calendarRangeQueryRootKey,
    {
      start: input.start,
      end: input.end,
      view: input.view,
      timezone: input.timezone ?? "default",
    },
  ] as const;
}

export function useCalendarRangeQuery(input: CalendarRangeQueryInput) {
  return useQuery({
    queryKey: calendarRangeQueryKey(input),
    queryFn: ({ signal }) => api.getCalendarRange(input, signal).then((response) => response.data),
    placeholderData: (previousData) => previousData,
  });
}

export function useInvalidateCalendarRange() {
  const queryClient = useQueryClient();
  return async () => {
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: calendarRangeQueryRootKey }),
      queryClient.invalidateQueries({ queryKey: ["sync", "status"] }),
      queryClient.invalidateQueries({ queryKey: ["sync", "conflicts", "pending"] }),
    ]);
  };
}
