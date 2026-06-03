"use client";

import { ChangeEvent, FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { QueryKey } from "@tanstack/react-query";

import { AppShell } from "@/components/app-shell";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { SuggestionReviewCard } from "@/components/suggestion-review-card";
import { calendarRangeQueryRootKey, useCalendarRangeQuery } from "@/hooks/use-calendar-range-query";
import { api, isConflictError } from "@/lib/api";
import { useBodyScrollLock } from "@/lib/use-body-scroll-lock";
import {
  formatAiActionLabel,
  formatClockValue,
  formatServiceCopy,
  formatUserMemo,
  getSuggestionDisplayState,
  getSuggestionNoticeDetail,
} from "@/lib/format";
import {
  CATEGORY_LABELS,
  DAY_FULL_LABELS,
  DAY_ORDER,
  durationInMinutes,
  getCurrentDayName,
  getCurrentMinutes,
  getDailyBlocks,
  isBlockActive,
  minutesFromClock,
} from "@/lib/schedule";
import {
  CalendarOccurrence,
  CalendarRangeResponse,
  CreateScheduleBlockRequest,
  RescheduleSuggestion,
  ScheduleBlock,
  WeekScheduleResponse,
} from "@/lib/types";
import { useSessionBootstrap } from "@/hooks/use-session-bootstrap";
import { useAppStore } from "@/stores/app-store";

const DEFAULT_FORM = {
  dayOfWeek: "MONDAY",
  startTime: "09:00",
  endTime: "10:00",
  activity: "",
  category: "WORK",
  note: "",
};

const DEFAULT_OCCURRENCE_FORM = {
  title: "",
  description: "",
  startAt: "",
  endAt: "",
  dueDate: "",
  estimatedMinutes: "30",
  priority: "3",
  category: "WORK",
  goalId: null,
};
const SCHEDULE_MUTATION_TIMEOUT_MS = 10_000;
const SCHEDULE_TIMELINE_VIEWS = ["month", "week", "day", "agenda"] as const;

type ScheduleTimelineView = (typeof SCHEDULE_TIMELINE_VIEWS)[number];

const SCHEDULE_TIMELINE_VIEW_LABELS: Record<ScheduleTimelineView, string> = {
  month: "월",
  week: "주",
  day: "일",
  agenda: "목록",
};

const SCHEDULE_TIMELINE_VIEW_DESCRIPTIONS: Record<ScheduleTimelineView, string> = {
  month: "월간 모자이크",
  week: "주간 스택",
  day: "선택한 하루",
  agenda: "시간순 목록",
};

interface ScheduleData {
  week: WeekScheduleResponse | null;
  suggestions: RescheduleSuggestion[];
}

type CalendarRangeSnapshot = Array<[QueryKey, CalendarRangeResponse | undefined]>;

interface ScheduleMutationSnapshot {
  calendarRanges: CalendarRangeSnapshot;
  week: WeekScheduleResponse | null;
}

interface CreateBlockMutationVariables {
  request: CreateScheduleBlockRequest;
  signal?: AbortSignal;
}

interface UpdateBlockMutationVariables {
  blockId: string;
  request: CreateScheduleBlockRequest;
  signal?: AbortSignal;
}

interface DeleteBlockMutationVariables {
  blockId: string;
  signal?: AbortSignal;
}

class ScheduleMutationTimeoutError extends Error {
  constructor() {
    super("일정 변경 요청이 10초 안에 끝나지 않아 중단했습니다. 네트워크 상태를 확인한 뒤 다시 시도하세요.");
    this.name = "ScheduleMutationTimeoutError";
  }
}

function isAbortError(error: unknown) {
  return error instanceof DOMException && error.name === "AbortError";
}

function mutationErrorDetail(error: unknown) {
  if (error instanceof ScheduleMutationTimeoutError) {
    return "네트워크 응답이 너무 느려 안전을 위해 요청을 취소했습니다. 입력한 내용은 유지되며, 잠시 후 다시 시도해 주세요.";
  }
  if (isConflictError(error)) {
    return "입력한 내용은 그대로 유지했습니다. 최신 일정표를 다시 불러왔으니 현재 서버 데이터와 비교한 뒤 다시 저장하세요.";
  }
  return error instanceof Error ? error.message : "잠시 후 다시 시도하면 됩니다.";
}

interface EditableScheduleBlock extends ScheduleBlock {
  dayOfWeek: string;
}

interface OccurrenceEditForm {
  title: string;
  description: string;
  startAt: string;
  endAt: string;
  dueDate: string;
  estimatedMinutes: string;
  priority: string;
  category: string;
  goalId: string | null;
}
function categoryTone(category: string) {
  if (category === "WORK") {
    return "deep";
  }
  if (category === "LIFE" || category === "HEALTH" || category === "SLEEP") {
    return "routine";
  }
  return "meeting";
}

function getWeekBlocks(week: WeekScheduleResponse | null) {
  return DAY_ORDER.flatMap((day) =>
    getDailyBlocks(week, day).map((block) => ({
      ...block,
      dayOfWeek: day,
    })),
  );
}

function shouldShowInWeeklyStack(block: ScheduleBlock) {
  return block.category !== "SLEEP";
}

function getVisibleWeekBlocks(week: WeekScheduleResponse | null) {
  return getWeekBlocks(week).filter(shouldShowInWeeklyStack);
}

function getVisibleDailyBlocks(week: WeekScheduleResponse | null, dayOfWeek: string) {
  return getDailyBlocks(week, dayOfWeek).filter(shouldShowInWeeklyStack);
}

function sortBlocks(blocks: ScheduleBlock[]) {
  return blocks.toSorted((left, right) => {
    if (left.startTime === right.startTime) {
      return left.endTime.localeCompare(right.endTime);
    }
    return left.startTime.localeCompare(right.startTime);
  });
}

function upsertWeekBlock(
  week: WeekScheduleResponse | null,
  dayOfWeek: string,
  block: ScheduleBlock,
) {
  if (!week) {
    return week;
  }

  let matchedDay = false;
  const nextWeek = week.week.map((day) => {
    if (day.dayOfWeek.toUpperCase() !== dayOfWeek.toUpperCase()) {
      return {
        ...day,
        blocks: day.blocks.filter((currentBlock) => currentBlock.id !== block.id),
      };
    }
    matchedDay = true;
    return {
      ...day,
      blocks: sortBlocks([
        ...day.blocks.filter((currentBlock) => currentBlock.id !== block.id),
        block,
      ]),
    };
  });

  if (!matchedDay) {
    nextWeek.push({ dayOfWeek, blocks: [block] });
  }

  return { ...week, week: nextWeek };
}

function removeWeekBlock(week: WeekScheduleResponse | null, blockId: string) {
  if (!week) {
    return week;
  }

  return {
    ...week,
    week: week.week.map((day) => ({
      ...day,
      blocks: day.blocks.filter((block) => block.id !== blockId),
    })),
  };
}

function toOptimisticBlock(
  request: CreateScheduleBlockRequest,
  previousBlock?: ScheduleBlock | null,
  id = previousBlock?.id ?? `optimistic-${crypto.randomUUID()}`,
): ScheduleBlock {
  return {
    id,
    startTime: request.startTime,
    endTime: request.endTime,
    activity: request.activity,
    category: request.category,
    note: request.note?.trim() ? request.note : null,
    sourceType: previousBlock?.sourceType ?? "MANUAL",
    sourceRef: previousBlock?.sourceRef ?? null,
  };
}

interface ZonedDateTimeParts {
  year: number;
  month: number;
  day: number;
  hour: number;
  minute: number;
  second: number;
}

function safeTimeZone(timeZone: string | null | undefined) {
  if (!timeZone) {
    return "UTC";
  }
  try {
    new Intl.DateTimeFormat("en-US", { timeZone }).format(new Date(0));
    return timeZone;
  } catch {
    return "UTC";
  }
}

function getZonedDateTimeParts(date: Date, timeZone: string): ZonedDateTimeParts {
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).formatToParts(date);
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]));
  return {
    year: Number(values.year),
    month: Number(values.month),
    day: Number(values.day),
    hour: Number(values.hour),
    minute: Number(values.minute),
    second: Number(values.second),
  };
}

function padDatePart(value: number) {
  return String(value).padStart(2, "0");
}

function zonedDateKey(date: Date, timeZone: string) {
  const parts = getZonedDateTimeParts(date, timeZone);
  return `${parts.year}-${padDatePart(parts.month)}-${padDatePart(parts.day)}`;
}

function addDaysToDateKey(dateKey: string, days: number) {
  const [year, month, day] = dateKey.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day + days, 0, 0, 0, 0));
  return date.toISOString().slice(0, 10);
}

function addMonthsToDateKey(dateKey: string, months: number) {
  const [year, month] = dateKey.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1 + months, 1, 0, 0, 0, 0));
  return date.toISOString().slice(0, 10);
}

function isCalendarDateKey(dateKey: string) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(dateKey)) {
    return false;
  }
  const [year, month, day] = dateKey.split("-").map(Number);
  const date = new Date(Date.UTC(year, month - 1, day, 0, 0, 0, 0));
  return !Number.isNaN(date.getTime()) && date.toISOString().slice(0, 10) === dateKey;
}

function getMonthRangeForDateKey(dateKey: string, timeZone: string) {
  const monthStartKey = `${dateKey.slice(0, 7)}-01`;
  const nextMonthStartKey = addMonthsToDateKey(monthStartKey, 1);
  return {
    monthStartKey,
    nextMonthStartKey,
    start: toIsoAtZonedDate(monthStartKey, "00:00", timeZone),
    end: toIsoAtZonedDate(nextMonthStartKey, "00:00", timeZone),
  };
}

function dayOfWeekFromDateKey(dateKey: string) {
  const [year, month, day] = dateKey.split("-").map(Number);
  return DAY_ORDER[(new Date(Date.UTC(year, month - 1, day)).getUTCDay() + 6) % 7];
}

function getTimeZoneOffsetMs(date: Date, timeZone: string) {
  const parts = getZonedDateTimeParts(date, timeZone);
  const zonedAsUtc = Date.UTC(
    parts.year,
    parts.month - 1,
    parts.day,
    parts.hour,
    parts.minute,
    parts.second,
    0,
  );
  return zonedAsUtc - date.getTime();
}

function toIsoAtZonedDate(dateKey: string, time: string, timeZone: string) {
  const [year, month, day] = dateKey.split("-").map(Number);
  const [hours, minutes] = time.split(":").map(Number);
  const localAsUtc = new Date(Date.UTC(year, month - 1, day, hours, minutes, 0, 0));
  const firstOffset = getTimeZoneOffsetMs(localAsUtc, timeZone);
  const firstInstant = new Date(localAsUtc.getTime() - firstOffset);
  const secondOffset = getTimeZoneOffsetMs(firstInstant, timeZone);
  return new Date(localAsUtc.getTime() - secondOffset).toISOString();
}

function zonedDateTimeInputValue(isoValue: string | null | undefined, timeZone: string) {
  if (!isoValue) {
    return "";
  }
  const date = new Date(isoValue);
  if (Number.isNaN(date.getTime())) {
    return "";
  }
  const parts = getZonedDateTimeParts(date, timeZone);
  return `${parts.year}-${padDatePart(parts.month)}-${padDatePart(parts.day)}T${padDatePart(parts.hour)}:${padDatePart(parts.minute)}`;
}

function isoFromZonedDateTimeInput(value: string, timeZone: string) {
  const [dateKey, time] = value.split("T");
  if (!dateKey || !time) {
    throw new Error("날짜와 시간을 모두 입력해 주세요.");
  }
  return toIsoAtZonedDate(dateKey, time, timeZone);
}
function projectOptimisticRoutineOccurrences(
  range: CalendarRangeResponse,
  dayOfWeek: string,
  block: ScheduleBlock,
): CalendarOccurrence[] {
  const start = new Date(range.start);
  const end = new Date(range.end);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()) || end.getTime() <= start.getTime()) {
    return [];
  }

  const occurrences: CalendarOccurrence[] = [];
  const timeZone = safeTimeZone(range.timezone);
  const endInclusive = new Date(end.getTime() - 1);
  let dateKey = zonedDateKey(start, timeZone);
  const endDateKey = zonedDateKey(endInclusive, timeZone);

  while (dateKey <= endDateKey) {
    if (dayOfWeekFromDateKey(dateKey) === dayOfWeek.toUpperCase()) {
      const endDateKeyForBlock = minutesFromClock(block.endTime) > minutesFromClock(block.startTime)
        ? dateKey
        : addDaysToDateKey(dateKey, 1);
      occurrences.push({
        occurrenceId: `routine:${block.id}:${dateKey}`,
        entityType: "ROUTINE_BLOCK",
        entityId: block.id,
        seriesId: block.id,
        startAt: toIsoAtZonedDate(dateKey, block.startTime, timeZone),
        endAt: toIsoAtZonedDate(endDateKeyForBlock, block.endTime, timeZone),
        title: block.activity,
        category: block.category,
        sourceType: block.sourceType,
        syncState: "LOCAL_ONLY",
        recurrenceInstanceType: "SINGLE",
        priorityTier: 30,
        collisionPolicy: "CAN_BE_SHADOWED",
        providerAuthority: "LOCAL_ROUTINE_ONLY",
        shadowState: "NONE",
        shadowingEntityType: null,
        shadowingEntityId: null,
        protectedWindow: false,
        synthetic: true,
      });
    }
    dateKey = addDaysToDateKey(dateKey, 1);
  }

  return occurrences.filter((occurrence) => {
    const startAt = occurrence.startAt ? new Date(occurrence.startAt) : null;
    const endAt = occurrence.endAt ? new Date(occurrence.endAt) : null;
    return Boolean(startAt && endAt && startAt < end && endAt > start);
  });
}

function upsertCalendarRoutine(
  range: CalendarRangeResponse,
  dayOfWeek: string,
  block: ScheduleBlock,
) {
  const withoutBlock = range.occurrences.filter((occurrence) => occurrence.entityId !== block.id);
  const projectedOccurrences = projectOptimisticRoutineOccurrences(range, dayOfWeek, block);
  const occurrences = [...withoutBlock, ...projectedOccurrences].toSorted((left, right) => {
    const leftStart = left.startAt ?? "";
    const rightStart = right.startAt ?? "";
    if (leftStart === rightStart) {
      return left.priorityTier - right.priorityTier || left.title.localeCompare(right.title);
    }
    return leftStart.localeCompare(rightStart);
  });
  return {
    ...range,
    occurrences,
    instrumentation: {
      ...range.instrumentation,
      occurrenceCount: occurrences.length,
    },
  };
}

function removeCalendarRoutine(range: CalendarRangeResponse, blockId: string) {
  const occurrences = range.occurrences.filter((occurrence) => occurrence.entityId !== blockId);
  return {
    ...range,
    occurrences,
    instrumentation: {
      ...range.instrumentation,
      occurrenceCount: occurrences.length,
    },
  };
}

function compareCalendarOccurrences(left: CalendarOccurrence, right: CalendarOccurrence) {
  const leftStart = left.startAt ?? left.endAt ?? "";
  const rightStart = right.startAt ?? right.endAt ?? "";
  if (leftStart !== rightStart) {
    if (!leftStart) {
      return 1;
    }
    if (!rightStart) {
      return -1;
    }
    return leftStart.localeCompare(rightStart);
  }
  return left.priorityTier - right.priorityTier || left.title.localeCompare(right.title);
}

function occurrenceLocalDateKeys(occurrence: CalendarOccurrence, timeZone: string) {
  const start = occurrence.startAt ? new Date(occurrence.startAt) : null;
  const end = occurrence.endAt ? new Date(occurrence.endAt) : null;
  const validStart = start && !Number.isNaN(start.getTime()) ? start : null;
  const validEnd = end && !Number.isNaN(end.getTime()) ? end : null;

  if (!validStart && !validEnd) {
    return [];
  }

  const startKey = zonedDateKey(validStart ?? validEnd!, timeZone);
  let endKey = startKey;
  if (validStart && validEnd && validEnd.getTime() > validStart.getTime()) {
    endKey = zonedDateKey(new Date(validEnd.getTime() - 1), timeZone);
  } else if (!validStart && validEnd) {
    endKey = zonedDateKey(validEnd, timeZone);
  }

  const keys: string[] = [];
  let cursor = startKey;
  for (let guard = 0; guard < 370; guard += 1) {
    keys.push(cursor);
    if (cursor === endKey) {
      break;
    }
    cursor = addDaysToDateKey(cursor, 1);
  }
  return keys;
}

function occurrenceDurationMinutesOnDate(occurrence: CalendarOccurrence, dateKey: string, timeZone: string) {
  if (!occurrence.startAt || !occurrence.endAt) {
    return 0;
  }
  const start = new Date(occurrence.startAt).getTime();
  const end = new Date(occurrence.endAt).getTime();
  if (Number.isNaN(start) || Number.isNaN(end) || end <= start) {
    return 0;
  }
  const dayStart = new Date(toIsoAtZonedDate(dateKey, "00:00", timeZone)).getTime();
  const dayEnd = new Date(toIsoAtZonedDate(addDaysToDateKey(dateKey, 1), "00:00", timeZone)).getTime();
  const clippedStart = Math.max(start, dayStart);
  const clippedEnd = Math.min(end, dayEnd);
  return clippedEnd > clippedStart ? Math.round((clippedEnd - clippedStart) / 60_000) : 0;
}

function groupAgendaOccurrences(range: CalendarRangeResponse | null | undefined, timeZone: string) {
  if (!range) {
    return [];
  }

  const groups = new Map<string, CalendarOccurrence[]>();
  range.occurrences
    .toSorted(compareCalendarOccurrences)
    .forEach((occurrence) => {
      const dateKeys = occurrenceLocalDateKeys(occurrence, timeZone);
      const keys = dateKeys.length ? dateKeys : ["unscheduled"];
      keys.forEach((dateKey) => {
        const occurrences = groups.get(dateKey) ?? [];
        occurrences.push(occurrence);
        groups.set(dateKey, occurrences);
      });
    });

  return Array.from(groups, ([dateKey, occurrences]) => ({
    dateKey,
    occurrences: occurrences.toSorted(compareCalendarOccurrences),
  })).toSorted((left, right) => {
    if (left.dateKey === right.dateKey) {
      return 0;
    }
    if (left.dateKey === "unscheduled") {
      return 1;
    }
    if (right.dateKey === "unscheduled") {
      return -1;
    }
    return left.dateKey.localeCompare(right.dateKey);
  });
}

function formatAgendaDateLabel(dateKey: string) {
  if (dateKey === "unscheduled") {
    return "날짜 미정";
  }
  const [year, month, day] = dateKey.split("-").map(Number);
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "UTC",
    month: "long",
    day: "numeric",
    weekday: "long",
  }).format(new Date(Date.UTC(year, month - 1, day)));
}

function formatOccurrenceClock(value: string | null, timeZone: string) {
  if (!value) {
    return null;
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return null;
  }
  const parts = getZonedDateTimeParts(date, timeZone);
  return `${padDatePart(parts.hour)}:${padDatePart(parts.minute)}`;
}

function formatOccurrenceTimeRange(occurrence: CalendarOccurrence, timeZone: string) {
  const start = formatOccurrenceClock(occurrence.startAt, timeZone);
  const end = formatOccurrenceClock(occurrence.endAt, timeZone);
  if (start && end && start !== end) {
    return `${start}–${end}`;
  }
  return start ?? end ?? "시간 미정";
}

function occurrenceKindLabel(occurrence: CalendarOccurrence) {
  if (occurrence.entityType === "EVENT") {
    return "이벤트";
  }
  if (occurrence.entityType === "TASK") {
    return "할 일";
  }
  return "루틴";
}

function agendaOccurrenceTone(occurrence: CalendarOccurrence) {
  if (occurrence.category) {
    return categoryTone(occurrence.category);
  }
  if (occurrence.entityType === "TASK") {
    return "deep";
  }
  return "meeting";
}

function buildAgendaRangeWindow(timeZoneInput: string | null | undefined) {
  const timeZone = safeTimeZone(timeZoneInput);
  const startDateKey = zonedDateKey(new Date(), timeZone);
  const endDateKey = addDaysToDateKey(startDateKey, 14);
  return {
    start: toIsoAtZonedDate(startDateKey, "00:00", timeZone),
    end: toIsoAtZonedDate(endDateKey, "00:00", timeZone),
    timeZone,
  };
}
function monthStartDateKey(dateKey: string) {
  const [year, month] = dateKey.split("-").map(Number);
  return new Date(Date.UTC(year, month - 1, 1, 0, 0, 0, 0)).toISOString().slice(0, 10);
}

function buildMonthRangeWindow(dateKey: string, timeZoneInput: string | null | undefined) {
  const timeZone = safeTimeZone(timeZoneInput);
  const safeDateKey = isCalendarDateKey(dateKey) ? dateKey : zonedDateKey(new Date(), timeZone);
  const startDateKey = monthStartDateKey(safeDateKey);
  const endDateKey = addMonthsToDateKey(startDateKey, 1);
  return {
    start: toIsoAtZonedDate(startDateKey, "00:00", timeZone),
    end: toIsoAtZonedDate(endDateKey, "00:00", timeZone),
    startDateKey,
    endDateKey,
    timeZone,
  };
}

function buildMonthGridDateKeys(monthDateKey: string) {
  const startDateKey = monthStartDateKey(monthDateKey);
  const firstDayIndex = DAY_ORDER.indexOf(dayOfWeekFromDateKey(startDateKey));
  const gridStart = addDaysToDateKey(startDateKey, -firstDayIndex);
  return Array.from({ length: 42 }, (_, index) => addDaysToDateKey(gridStart, index));
}

function isSameMonth(dateKey: string, monthDateKey: string) {
  return dateKey.slice(0, 7) === monthDateKey.slice(0, 7);
}

function groupOccurrencesByDate(range: CalendarRangeResponse | null | undefined, timeZone: string) {
  const groups = new Map<string, CalendarOccurrence[]>();
  range?.occurrences
    .toSorted(compareCalendarOccurrences)
    .forEach((occurrence) => {
      occurrenceLocalDateKeys(occurrence, timeZone).forEach((dateKey) => {
        const dayOccurrences = groups.get(dateKey) ?? [];
        dayOccurrences.push(occurrence);
        groups.set(dateKey, dayOccurrences);
      });
    });

  groups.forEach((occurrences, dateKey) => {
    groups.set(dateKey, occurrences.toSorted(compareCalendarOccurrences));
  });
  return groups;
}

function getOccurrencesForDate(
  range: CalendarRangeResponse | null | undefined,
  dateKey: string,
  timeZone: string,
) {
  return groupOccurrencesByDate(range, timeZone).get(dateKey) ?? [];
}

function filterWeekToDate(week: WeekScheduleResponse | null, dateKey: string): WeekScheduleResponse | null {
  if (!week) {
    return week;
  }
  const selectedDay = dayOfWeekFromDateKey(dateKey);
  return {
    week: week.week.map((day) => ({
      ...day,
      blocks: day.dayOfWeek === selectedDay ? day.blocks : [],
    })),
  };
}


function formatDurationLabel(totalMinutes: number) {
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;

  if (hours === 0) {
    return `${minutes}분`;
  }

  return minutes === 0 ? `${hours}시간` : `${hours}시간 ${minutes}분`;
}

function visibleScheduleNote(note: string | null | undefined) {
  return formatUserMemo(note);
}

function scheduleBlockActionLabel(dayOfWeek: string, block: ScheduleBlock, stateLabel?: string) {
  const pieces = [
    stateLabel,
    DAY_FULL_LABELS[dayOfWeek],
    `${formatClockValue(block.startTime)}부터 ${formatClockValue(block.endTime)}까지`,
    formatServiceCopy(block.activity),
    CATEGORY_LABELS[block.category] ? `${CATEGORY_LABELS[block.category]} 일정` : null,
  ].filter(Boolean);
  const note = visibleScheduleNote(block.note);
  return `${pieces.join(", ")}. ${note ? `메모: ${note}. ` : ""}선택하면 일정 편집 창을 엽니다.`;
}

function WeeklyStack({
  week,
  onBlockSelect,
  timeZone,
}: {
  week: WeekScheduleResponse | null;
  onBlockSelect: (block: EditableScheduleBlock) => void;
  timeZone?: string;
}) {
  const currentDay = getCurrentDayName(timeZone);
  const currentMinutes = getCurrentMinutes(timeZone);
  const weekBlocks = getVisibleWeekBlocks(week);
  const totalBlocks = weekBlocks.length;
  const totalMinutes = weekBlocks.reduce(
    (sum, block) => sum + durationInMinutes(block.startTime, block.endTime),
    0,
  );
  const busyDays = DAY_ORDER.filter((day) => getVisibleDailyBlocks(week, day).length > 0).length;
  const todayBlocks = getVisibleDailyBlocks(week, currentDay);
  const activeScheduleBlock = todayBlocks.find((block) => isBlockActive(block, currentMinutes)) ?? null;
  const nextScheduleBlock = todayBlocks.find((block) => minutesFromClock(block.startTime) > currentMinutes) ?? null;
  const focusScheduleBlock = activeScheduleBlock ?? nextScheduleBlock ?? todayBlocks[0] ?? null;
  const focusScheduleLabel = activeScheduleBlock ? "지금 일정" : "다음 일정";
  const mobileDays = [currentDay, ...DAY_ORDER.filter((day) => day !== currentDay)];

  return (
    <>
      <div className="mobile-week-agenda" aria-label="모바일 주간 일정 목록">
        <section className="mobile-schedule-brief">
          <p className="panel-kicker">오늘 보기</p>
          <div className="mobile-brief-grid">
            <span>
              <strong>{todayBlocks.length}</strong>
              오늘 일정
            </span>
            <span>
              <strong>{focusScheduleBlock ? formatClockValue(focusScheduleBlock.startTime) : "없음"}</strong>
              지금/다음
            </span>
            <span>
              <strong>{busyDays}</strong>
              활동 요일
            </span>
          </div>
          <p>
            오늘 일정과 지금 할 일을 먼저 보여줍니다.
          </p>
          <div className={`mobile-now-card ${focusScheduleBlock ? categoryTone(focusScheduleBlock.category) : "empty"}`}>
            <span>{focusScheduleLabel}</span>
            <strong>{focusScheduleBlock ? formatServiceCopy(focusScheduleBlock.activity) : "표시할 일정이 없습니다."}</strong>
            {focusScheduleBlock ? (
              <small>
                {formatClockValue(focusScheduleBlock.startTime)} - {formatClockValue(focusScheduleBlock.endTime)}
              </small>
            ) : null}
          </div>
        </section>
        {mobileDays.map((day) => {
          const isToday = currentDay === day;
          const blocks = getVisibleDailyBlocks(week, day);
          const previewBlocks = blocks.slice(0, isToday ? 4 : 3);
          const cardContent = (
            <>
              {blocks.length ? (
                <div className="mobile-day-blocks">
                  {previewBlocks.map((block) => (
                    (() => {
                      const note = visibleScheduleNote(block.note);
                      return (
                        <button
                          key={block.id}
                          type="button"
                          aria-label={scheduleBlockActionLabel(day, block)}
                          className={`mobile-schedule-block ${categoryTone(block.category)}`}
                          onClick={() =>
                            onBlockSelect({
                              ...block,
                              dayOfWeek: day,
                            })
                          }
                        >
                          <span className="event-time">
                            {formatClockValue(block.startTime)} - {formatClockValue(block.endTime)}
                          </span>
                          <strong>{formatServiceCopy(block.activity)}</strong>
                          {note ? <span>{note}</span> : null}
                        </button>
                      );
                    })()
                  ))}
                  {blocks.length > previewBlocks.length ? (
                    <p className="mobile-empty-day">
                      나머지 {blocks.length - previewBlocks.length}개 일정
                    </p>
                  ) : null}
                </div>
              ) : (
                <p className="mobile-empty-day">
                  비어 있는 시간입니다.
                </p>
              )}
            </>
          );

          return (
            <section className={`mobile-day-card ${isToday ? "today" : ""}`} key={day}>
              <div className="mobile-day-head">
                <strong>{isToday ? "오늘 · " : ""}{DAY_FULL_LABELS[day]}</strong>
                <span>{blocks.length ? `${blocks.length}개 일정` : "비어 있음"}</span>
              </div>
              {cardContent}
            </section>
          );
        })}
      </div>

      <div className="week-stack-shell">
        <div className="week-planner-head">
          <div>
            <p className="panel-kicker">주간 일정</p>
            <h2>이번 주 일정</h2>
            <p>
              요일별 일정을 아침부터 밤까지 순서대로 보여줍니다.
            </p>
          </div>
          <div className="week-summary-strip" aria-label="주간 일정 요약">
            <span>
              <strong>{totalBlocks}</strong>
              표시 일정
            </span>
            <span>
              <strong>{formatDurationLabel(totalMinutes)}</strong>
              총 일정 시간
            </span>
            <span>
              <strong>{busyDays}</strong>
              활동 요일
            </span>
          </div>
        </div>
        {focusScheduleBlock ? (
          (() => {
            const note = visibleScheduleNote(focusScheduleBlock.note);
            return (
              <button
                className={`current-schedule-card ${categoryTone(focusScheduleBlock.category)}`}
                type="button"
                aria-label={scheduleBlockActionLabel(currentDay, focusScheduleBlock, focusScheduleLabel)}
                onClick={() =>
                  onBlockSelect({
                    ...focusScheduleBlock,
                    dayOfWeek: currentDay,
                  })
                }
              >
                <span className="current-schedule-kicker">{focusScheduleLabel}</span>
                <strong>{formatServiceCopy(focusScheduleBlock.activity)}</strong>
                <span className="current-schedule-time">
                  {formatClockValue(focusScheduleBlock.startTime)} - {formatClockValue(focusScheduleBlock.endTime)}
                </span>
                {note ? <span className="current-schedule-note">{note}</span> : null}
              </button>
            );
          })()
        ) : (
          <div className="current-schedule-card empty" aria-label="현재 또는 다음 일정 없음">
            <span className="current-schedule-kicker">지금 일정</span>
            <strong>표시할 일정이 없습니다.</strong>
            <span className="current-schedule-time">필요한 일정은 직접 추가할 수 있습니다.</span>
          </div>
        )}

        <div className="week-stack-board" aria-label="주간 일정 스택">
          {DAY_ORDER.map((day) => {
            const isToday = currentDay === day;
            const blocks = getVisibleDailyBlocks(week, day);
            return (
              <section key={day} className={`week-stack-day ${isToday ? "today" : ""}`}>
                <div className="week-stack-day-head">
                  <strong>{isToday ? "오늘 · " : ""}{DAY_FULL_LABELS[day]}</strong>
                  <span>{blocks.length ? `${blocks.length}개 일정` : "비어 있음"}</span>
                </div>

                {blocks.length ? (
                  <div className="week-stack-list">
                    {blocks.map((block) => {
                      const isCurrentBlock = day === currentDay && isBlockActive(block, currentMinutes);
                      const note = visibleScheduleNote(block.note);
                      return (
                        <button
                          key={block.id}
                          type="button"
                          aria-label={scheduleBlockActionLabel(day, block, isCurrentBlock ? "지금 일정" : undefined)}
                          className={`schedule-block week-stack-block ${categoryTone(block.category)} ${isCurrentBlock ? "current" : ""}`}
                          onClick={() =>
                            onBlockSelect({
                              ...block,
                              dayOfWeek: day,
                            })
                          }
                        >
                          <span className="event-time">
                            {formatClockValue(block.startTime)} - {formatClockValue(block.endTime)}
                          </span>
                          <strong>{formatServiceCopy(block.activity)}</strong>
                          {isCurrentBlock ? <span className="current-event-chip">지금 일정</span> : null}
                          {note ? <p className="event-note">{note}</p> : null}
                        </button>
                      );
                    })}
                  </div>
                ) : (
                  <p className="week-stack-empty">비어 있는 시간입니다.</p>
                )}
              </section>
            );
          })}
        </div>
      </div>
    </>
  );
}

interface AiDraftProjectionItem {
  key: string;
  actionType: string;
  targetType: string;
  title: string;
  detail: string;
  reason: string | null;
  startAt: string | null;
  endAt: string | null;
}

function payloadString(payload: Record<string, unknown> | undefined, ...keys: string[]) {
  if (!payload) {
    return null;
  }
  for (const key of keys) {
    const value = payload[key];
    if (typeof value === "string" && value.trim()) {
      return value.trim();
    }
    if (typeof value === "number" && Number.isFinite(value)) {
      return String(value);
    }
  }
  return null;
}

function draftProjectionDetail(payload: Record<string, unknown> | undefined, timeZone: string) {
  const dayOfWeek = payloadString(payload, "dayOfWeek", "day_of_week");
  const startTime = payloadString(payload, "startTime", "start_time");
  const endTime = payloadString(payload, "endTime", "end_time");
  if (dayOfWeek && startTime && endTime) {
    return `${dayOfWeek} ${startTime}-${endTime}`;
  }
  if (startTime && endTime) {
    return `${startTime}-${endTime}`;
  }

  const startAt = payloadString(payload, "startAt", "start_at");
  const endAt = payloadString(payload, "endAt", "end_at");
  if (startAt && endAt) {
    return `${formatOccurrenceClock(startAt, timeZone)}-${formatOccurrenceClock(endAt, timeZone)}`;
  }

  const shiftMinutes = payloadString(payload, "suggestedShiftMinutes", "suggested_shift_minutes");
  if (shiftMinutes) {
    return `${shiftMinutes}분 이동`;
  }
  return "시간 세부값은 제안 카드에서 확인";
}

function buildAiDraftProjectionItems(suggestions: RescheduleSuggestion[], timeZone: string) {
  return suggestions
    .filter((suggestion) => suggestion.status === "pending")
    .flatMap((suggestion) =>
      suggestion.commandBatch.commands
        .filter((command) => command.executable && command.actionType !== "explain_only")
        .map((command, index) => {
          const payload = command.payload;
          const title = payloadString(payload, "activity", "title", "summary")
            ?? suggestion.previewItems.find((item) => item.executable && item.actionType === command.actionType)?.title
            ?? suggestion.summary;
          const startAt = payloadString(payload, "startAt", "start_at");
          const endAt = payloadString(payload, "endAt", "end_at");
          return {
            key: `${suggestion.id}:${command.actionType}:${command.targetId ?? index}`,
            actionType: command.actionType,
            targetType: command.targetType,
            title,
            detail: draftProjectionDetail(payload, timeZone),
            reason: command.reason ?? suggestion.reason,
            startAt,
            endAt,
          } satisfies AiDraftProjectionItem;
        }),
    )
    .slice(0, 6);
}

function isSuggestionInCurrentPageSession(
  suggestion: RescheduleSuggestion,
  sessionStartedAt: number,
  sessionSuggestionIds: ReadonlySet<string>,
) {
  if (sessionSuggestionIds.has(suggestion.id)) {
    return true;
  }

  const createdAt = Date.parse(suggestion.createdAt);
  return Number.isFinite(createdAt) && createdAt >= sessionStartedAt;
}

function AiDraftProjection({
  draftItems,
}: {
  draftItems: AiDraftProjectionItem[];
}) {
  if (!draftItems.length) {
    return null;
  }

  return (
    <section className="ai-draft-projection" data-testid="ai-draft-projection" aria-label="AI 변경안 타임라인 투영">
      <div className="ai-draft-projection-head">
        <div>
          <p className="panel-kicker">AI Draft Projection</p>
          <strong>아직 적용 전인 변경안을 타임라인 위에서 먼저 확인합니다.</strong>
        </div>
        <span>{draftItems.length}개 Draft</span>
      </div>
      <ol className="ai-draft-projection-list">
        {draftItems.map((item) => (
          <li className="ai-draft-projection-item" key={item.key} data-testid="ai-draft-projection-item">
            <span>{formatAiActionLabel(item.actionType)} · {item.targetType}</span>
            <strong>{formatServiceCopy(item.title)}</strong>
            <small>{item.detail}</small>
            {item.reason ? <p>{formatServiceCopy(item.reason)}</p> : null}
          </li>
        ))}
      </ol>
    </section>
  );
}
function MonthlyMosaic({
  range,
  isLoading,
  isError,
  selectedDateKey,
  monthDateKey,
  timeZone,
  onSelectDate,
}: {
  range: CalendarRangeResponse | null | undefined;
  isLoading: boolean;
  isError: boolean;
  selectedDateKey: string;
  monthDateKey: string;
  timeZone: string;
  onSelectDate: (dateKey: string) => void;
}) {
  const monthDays = useMemo(() => buildMonthGridDateKeys(monthDateKey), [monthDateKey]);
  const occurrencesByDate = useMemo(() => groupOccurrencesByDate(range, timeZone), [range, timeZone]);
  const monthLabel = useMemo(
    () => formatAgendaDateLabel(monthStartDateKey(monthDateKey)).replace(/\s*1일.*$/, ""),
    [monthDateKey],
  );

  return (
    <section className="monthly-mosaic-card" data-testid="monthly-mosaic" aria-label="월간 모자이크">
      <div className="monthly-mosaic-head">
        <div>
          <p className="panel-kicker">Monthly Mosaic</p>
          <h2>{monthLabel}</h2>
          <p>기존 캘린더 range 응답을 날짜별 결정적 요약으로 압축합니다.</p>
        </div>
        <span>{range?.instrumentation.occurrenceCount ?? 0}개 항목</span>
      </div>

      {isLoading ? <p className="timeline-state-note">월간 데이터를 불러오는 중입니다.</p> : null}
      {isError ? <p className="timeline-state-note error">월간 데이터를 불러오지 못했습니다. 주간 보기는 계속 사용할 수 있습니다.</p> : null}

      <div className="monthly-weekday-row" aria-hidden="true">
        {DAY_ORDER.map((day) => <span key={day}>{DAY_FULL_LABELS[day].slice(0, 1)}</span>)}
      </div>
      <div className="monthly-mosaic-grid">
        {monthDays.map((dateKey) => {
          const occurrences = occurrencesByDate.get(dateKey) ?? [];
          const totalMinutes = occurrences.reduce(
            (sum, occurrence) => sum + occurrenceDurationMinutesOnDate(occurrence, dateKey, timeZone),
            0,
          );
          const isSelected = dateKey === selectedDateKey;
          const isOutsideMonth = !isSameMonth(dateKey, monthDateKey);
          return (
            <button
              aria-pressed={isSelected}
              className={["monthly-mosaic-day", isSelected ? "selected" : "", isOutsideMonth ? "outside" : ""].filter(Boolean).join(" ")}
              data-testid="monthly-mosaic-day"
              key={dateKey}
              type="button"
              onClick={() => onSelectDate(dateKey)}
            >
              <span className="monthly-day-number">{Number(dateKey.slice(-2))}</span>
              {occurrences.length ? (
                <>
                  <strong>{occurrences.length}개 일정</strong>
                  <small>{formatDurationLabel(totalMinutes)}</small>
                  <span className="monthly-day-preview">{formatServiceCopy(occurrences[0].title)}</span>
                </>
              ) : (
                <small>비어 있음</small>
              )}
            </button>
          );
        })}
      </div>
    </section>
  );
}


function draftItemDateKeys(item: AiDraftProjectionItem, timeZone: string) {
  if (!item.startAt && !item.endAt) {
    return [];
  }
  const occurrence: CalendarOccurrence = {
    occurrenceId: item.key,
    entityType: item.targetType.toLowerCase() === "task" ? "TASK" : "EVENT",
    entityId: item.key,
    seriesId: item.key,
    startAt: item.startAt,
    endAt: item.endAt,
    title: item.title,
    category: null,
    sourceType: "AI_DRAFT",
    syncState: "DRAFT",
    recurrenceInstanceType: "SINGLE",
    priorityTier: 5,
    collisionPolicy: "CAN_BE_SHADOWED",
    providerAuthority: "LOCAL_CAN_WRITE",
    shadowState: "NONE",
    shadowingEntityType: null,
    shadowingEntityId: null,
    protectedWindow: false,
    synthetic: true,
  };
  return occurrenceLocalDateKeys(occurrence, timeZone).filter(isCalendarDateKey);
}

function draftItemsForDate(items: AiDraftProjectionItem[], dateKey: string, timeZone: string) {
  return items
    .filter((item) => draftItemDateKeys(item, timeZone).includes(dateKey))
    .toSorted((left, right) => (left.startAt ?? "").localeCompare(right.startAt ?? "") || left.title.localeCompare(right.title));
}
function SelectedDayTimeline({
  week,
  range,
  isRangeLoading,
  isRangeError,
  selectedDateKey,
  draftItems,
  onBlockSelect,
  onOccurrenceSelect,
  onReturnToAgenda,
  onReturnToMonth,
  timeZone,
}: {
  week: WeekScheduleResponse | null;
  range: CalendarRangeResponse | null | undefined;
  isRangeLoading: boolean;
  isRangeError: boolean;
  selectedDateKey: string;
  draftItems: AiDraftProjectionItem[];
  onBlockSelect: (block: EditableScheduleBlock) => void;
  onOccurrenceSelect: (occurrence: CalendarOccurrence) => void;
  onReturnToAgenda: () => void;
  onReturnToMonth: () => void;
  timeZone: string;
}) {
  const selectedWeek = useMemo(() => filterWeekToDate(week, selectedDateKey), [week, selectedDateKey]);
  const selectedOccurrences = useMemo(
    () => getOccurrencesForDate(range, selectedDateKey, timeZone),
    [range, selectedDateKey, timeZone],
  );
  const selectedDraftItems = useMemo(
    () => draftItemsForDate(draftItems, selectedDateKey, timeZone),
    [draftItems, selectedDateKey, timeZone],
  );
  return (
    <section className="selected-day-card" data-testid="selected-day-timeline" aria-label="선택한 하루 일정">
      <div className="selected-day-head">
        <div>
          <p className="panel-kicker">Selected Day</p>
          <h2>{formatAgendaDateLabel(selectedDateKey)}</h2>
          <p>월간/목록에서 고른 날짜를 5분 오케스트레이션의 일간 맥락으로 넘깁니다.</p>
        </div>
        <div className="selected-day-actions" aria-label="일간 보기 전환">
          <button type="button" className="ghost-btn" onClick={onReturnToMonth}>월간으로</button>
          <button type="button" className="ghost-btn" onClick={onReturnToAgenda}>목록으로</button>
        </div>
      </div>

      <section className="selected-day-range" aria-label="캘린더 range 항목">
        <div className="selected-day-range-head">
          <strong>Range spine 항목</strong>
          <span>{selectedOccurrences.length + selectedDraftItems.length}개</span>
        </div>
        {isRangeLoading ? <p className="timeline-state-note">선택한 날짜의 캘린더 항목을 불러오는 중입니다.</p> : null}
        {isRangeError ? <p className="timeline-state-note error">선택한 날짜의 캘린더 항목을 불러오지 못했습니다. 로컬 주간 일정은 계속 표시합니다.</p> : null}
        {selectedDraftItems.length ? (
          <ol className="selected-day-occurrence-list selected-day-draft-list" aria-label="AI Draft 시간 투영">
            {selectedDraftItems.map((draft) => (
              <li className="selected-day-occurrence ai-draft-occurrence" data-testid="selected-day-draft-occurrence" key={draft.key}>
                <div className="occurrence-edit-trigger" aria-label={`${draft.title} draft`}>
                  <span className="agenda-occurrence-time">{draft.detail}</span>
                  <div>
                    <strong>{formatServiceCopy(draft.title)}</strong>
                    <span>AI Draft · 적용 전</span>
                  </div>
                </div>
              </li>
            ))}
          </ol>
        ) : null}
        {!isRangeLoading && !isRangeError && selectedOccurrences.length === 0 && selectedDraftItems.length === 0 ? (
          <p className="week-stack-empty">캘린더 range 항목이 없습니다.</p>
        ) : null}
        {selectedOccurrences.length ? (
          <ol className="selected-day-occurrence-list">
            {selectedOccurrences.map((occurrence) => (
              <li
                className={`selected-day-occurrence ${agendaOccurrenceTone(occurrence)}`}
                data-testid="selected-day-occurrence"
                key={occurrence.occurrenceId}
              >
                <button className="occurrence-edit-trigger" type="button" onClick={() => onOccurrenceSelect(occurrence)}>
                  <span className="agenda-occurrence-time">{formatOccurrenceTimeRange(occurrence, timeZone)}</span>
                  <div>
                    <strong>{formatServiceCopy(occurrence.title)}</strong>
                    <span>
                      {occurrenceKindLabel(occurrence)}
                      {occurrence.synthetic ? " · 반복" : ""}
                    </span>
                  </div>
                </button>
              </li>
            ))}
          </ol>
        ) : null}
      </section>

      <WeeklyStack week={selectedWeek} onBlockSelect={onBlockSelect} timeZone={timeZone} />
    </section>
  );
}

function AgendaStream({
  range,
  isLoading,
  isError,
  timeZone,
  onSelectDate,
  onOccurrenceSelect,
}: {
  range: CalendarRangeResponse | null | undefined;
  isLoading: boolean;
  isError: boolean;
  timeZone: string;
  onSelectDate: (dateKey: string) => void;
  onOccurrenceSelect: (occurrence: CalendarOccurrence) => void;
}) {
  const groups = useMemo(() => groupAgendaOccurrences(range, timeZone), [range, timeZone]);
  const occurrenceCount = useMemo(
    () => groups.reduce((sum, group) => sum + group.occurrences.length, 0),
    [groups],
  );

  return (
    <section className="agenda-stream-card" data-testid="agenda-stream" aria-label="아젠다 스트림">
      <div className="agenda-stream-head">
        <div>
          <p className="panel-kicker">Agenda</p>
          <h2>다가오는 일정 흐름</h2>
          <p>캘린더 범위 응답의 발생 항목을 현지 날짜별로 묶어 시간순으로 보여줍니다.</p>
        </div>
        <span>{occurrenceCount}개</span>
      </div>

      {isLoading ? (
        <p className="agenda-stream-empty">아젠다를 불러오는 중입니다.</p>
      ) : null}

      {isError ? (
        <p className="agenda-stream-empty">아젠다를 불러오지 못했습니다. 주간 일정은 계속 사용할 수 있습니다.</p>
      ) : null}

      {!isLoading && !isError && groups.length === 0 ? (
        <p className="agenda-stream-empty">표시할 아젠다가 없습니다.</p>
      ) : null}

      {!isLoading && !isError && groups.length > 0 ? (
        <div className="agenda-stream-groups">
          {groups.map((group) => {
            const canOpenDay = isCalendarDateKey(group.dateKey);
            return (
            <section className="agenda-day-group" key={group.dateKey} data-testid="agenda-day-group">
              <button
                className="agenda-day-head"
                type="button"
                disabled={!canOpenDay}
                onClick={() => {
                  if (canOpenDay) {
                    onSelectDate(group.dateKey);
                  }
                }}
              >
                <strong>{formatAgendaDateLabel(group.dateKey)}</strong>
                <span>{canOpenDay ? group.occurrences.length + "개 · 일간 보기" : group.occurrences.length + "개 · 날짜 미정"}</span>
              </button>
              <ol className="agenda-occurrence-list">
                {group.occurrences.map((occurrence) => (
                  <li
                    className={`agenda-occurrence ${agendaOccurrenceTone(occurrence)}`}
                    data-testid="agenda-occurrence"
                    key={occurrence.occurrenceId}
                  >
                    <button className="occurrence-edit-trigger" type="button" onClick={() => onOccurrenceSelect(occurrence)}>
                      <span className="agenda-occurrence-time">
                        {formatOccurrenceTimeRange(occurrence, timeZone)}
                      </span>
                      <div>
                        <strong>{formatServiceCopy(occurrence.title)}</strong>
                        <span>
                          {occurrenceKindLabel(occurrence)}
                          {occurrence.synthetic ? " · 반복" : ""}
                        </span>
                      </div>
                    </button>
                  </li>
                ))}
              </ol>
            </section>
            );
          })}
        </div>
      ) : null}
    </section>
  );
}

export function ScheduleView() {
  const { session, refreshSession } = useSessionBootstrap();
  const showNotice = useAppStore((state) => state.showNotice);
  const [data, setData] = useState<ScheduleData>({
    week: null,
    suggestions: [],
  });
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState(DEFAULT_FORM);
  const [requestReason, setRequestReason] = useState("");
  const [formConflictDetail, setFormConflictDetail] = useState<string | null>(null);
  const [blockingConflictMessage, setBlockingConflictMessage] = useState<string | null>(null);
  const [isCreateModalOpen, setCreateModalOpen] = useState(false);
  const [editingBlock, setEditingBlock] = useState<EditableScheduleBlock | null>(null);
  const [editingOccurrence, setEditingOccurrence] = useState<CalendarOccurrence | null>(null);
  const [occurrenceForm, setOccurrenceForm] = useState<OccurrenceEditForm>(DEFAULT_OCCURRENCE_FORM);
  const [deleteCandidate, setDeleteCandidate] = useState<EditableScheduleBlock | null>(null);
  const [isMutating, setIsMutating] = useState(false);
  const [timelineView, setTimelineView] = useState<ScheduleTimelineView>("week");
  const [selectedDateKey, setSelectedDateKey] = useState(() => zonedDateKey(new Date(), safeTimeZone(session?.timezone)));
  const activityFieldRef = useRef<HTMLInputElement | null>(null);
  const requestInputRef = useRef<HTMLTextAreaElement | null>(null);
  const aiThreadRef = useRef<HTMLDivElement | null>(null);
  const aiThreadEndRef = useRef<HTMLDivElement | null>(null);
  const shouldStickToAiThreadBottomRef = useRef(true);
  const aiConversationStartedAtRef = useRef(Date.now());
  const aiConversationSuggestionIdsRef = useRef<Set<string>>(new Set());
  const selectedDateTimeZoneRef = useRef<string | null>(null);
  const loadSequenceRef = useRef(0);
  const scheduleMutationQueueRef = useRef<Promise<void>>(Promise.resolve());
  const scheduleMutationActiveRef = useRef(false);
  const queryClient = useQueryClient();
  const scheduleTimeZone = safeTimeZone(session?.timezone);
  const agendaRangeWindow = useMemo(
    () => buildAgendaRangeWindow(session?.timezone),
    [session?.timezone],
  );
  const monthRangeWindow = useMemo(
    () => buildMonthRangeWindow(selectedDateKey, session?.timezone),
    [selectedDateKey, session?.timezone],
  );
  const agendaRangeQuery = useCalendarRangeQuery(
    {
      start: agendaRangeWindow.start,
      end: agendaRangeWindow.end,
      view: "agenda",
      timezone: agendaRangeWindow.timeZone,
    },
    { enabled: Boolean(session?.authenticated) && timelineView === "agenda" },
  );
  const monthRangeQuery = useCalendarRangeQuery(
    {
      start: monthRangeWindow.start,
      end: monthRangeWindow.end,
      view: "month",
      timezone: monthRangeWindow.timeZone,
    },
    { enabled: Boolean(session?.authenticated) && (timelineView === "month" || timelineView === "day") },
  );

  async function runScheduleMutationExclusive<T>(operation: (signal: AbortSignal) => Promise<T>) {
    const previousMutation = scheduleMutationQueueRef.current;
    let releaseCurrentMutation!: () => void;
    const currentMutation = new Promise<void>((resolve) => {
      releaseCurrentMutation = resolve;
    });
    scheduleMutationQueueRef.current = previousMutation
      .catch(() => undefined)
      .then(() => currentMutation);

    await previousMutation.catch(() => undefined);
    const controller = new AbortController();
    let timeoutId: ReturnType<typeof setTimeout> | null = null;
    const timeoutPromise = new Promise<never>((_resolve, reject) => {
      timeoutId = setTimeout(() => {
        controller.abort();
        reject(new ScheduleMutationTimeoutError());
      }, SCHEDULE_MUTATION_TIMEOUT_MS);
    });
    try {
      return await Promise.race([operation(controller.signal), timeoutPromise]);
    } finally {
      if (timeoutId) {
        clearTimeout(timeoutId);
      }
      releaseCurrentMutation();
    }
  }

  async function snapshotScheduleMutation(): Promise<ScheduleMutationSnapshot> {
    await queryClient.cancelQueries({ queryKey: calendarRangeQueryRootKey });
    return {
      calendarRanges: queryClient.getQueriesData<CalendarRangeResponse>({
        queryKey: calendarRangeQueryRootKey,
      }),
      week: data.week,
    };
  }

  function restoreScheduleMutation(snapshot: ScheduleMutationSnapshot | undefined) {
    if (!snapshot) {
      return;
    }
    snapshot.calendarRanges.forEach(([queryKey, range]) => {
      queryClient.setQueryData(queryKey, range);
    });
    setData((current) => ({
      ...current,
      week: snapshot.week,
    }));
  }

  function updateCalendarRangeCaches(
    updater: (range: CalendarRangeResponse) => CalendarRangeResponse,
  ) {
    queryClient.getQueriesData<CalendarRangeResponse>({ queryKey: calendarRangeQueryRootKey })
      .forEach(([queryKey, range]) => {
        if (!range) {
          return;
        }
        queryClient.setQueryData(queryKey, updater(range));
      });
  }

  const createBlockMutation = useMutation({
    mutationKey: ["schedule", "block", "create"],
    scope: { id: "schedule-block-cud" },
    mutationFn: ({ request, signal }: CreateBlockMutationVariables) =>
      api.createScheduleBlock(request, signal),
    onMutate: async ({ request }) => {
      const snapshot = await snapshotScheduleMutation();
      const optimisticBlock = toOptimisticBlock(request);
      setData((current) => ({
        ...current,
        week: upsertWeekBlock(current.week, request.dayOfWeek, optimisticBlock),
      }));
      updateCalendarRangeCaches((range) => upsertCalendarRoutine(range, request.dayOfWeek, optimisticBlock));
      return snapshot;
    },
    onError: (_error, _request, snapshot) => {
      restoreScheduleMutation(snapshot);
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: calendarRangeQueryRootKey });
    },
  });

  const updateBlockMutation = useMutation({
    mutationKey: ["schedule", "block", "update"],
    scope: { id: "schedule-block-cud" },
    mutationFn: ({ blockId, request, signal }: UpdateBlockMutationVariables) =>
      api.updateScheduleBlock(blockId, request, signal),
    onMutate: async ({ blockId, request }) => {
      const snapshot = await snapshotScheduleMutation();
      const previousBlock = getWeekBlocks(data.week).find((block) => block.id === blockId) ?? null;
      const optimisticBlock = toOptimisticBlock(request, previousBlock, blockId);
      setData((current) => ({
        ...current,
        week: upsertWeekBlock(current.week, request.dayOfWeek, optimisticBlock),
      }));
      updateCalendarRangeCaches((range) => upsertCalendarRoutine(range, request.dayOfWeek, optimisticBlock));
      return snapshot;
    },
    onError: (_error, _variables, snapshot) => {
      restoreScheduleMutation(snapshot);
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: calendarRangeQueryRootKey });
    },
  });

  const deleteBlockMutation = useMutation({
    mutationKey: ["schedule", "block", "delete"],
    scope: { id: "schedule-block-cud" },
    mutationFn: ({ blockId, signal }: DeleteBlockMutationVariables) =>
      api.deleteScheduleBlock(blockId, signal),
    onMutate: async ({ blockId }) => {
      const snapshot = await snapshotScheduleMutation();
      setData((current) => ({
        ...current,
        week: removeWeekBlock(current.week, blockId),
      }));
      updateCalendarRangeCaches((range) => removeCalendarRoutine(range, blockId));
      return snapshot;
    },
    onError: (_error, _blockId, snapshot) => {
      restoreScheduleMutation(snapshot);
    },
    onSettled: async () => {
      await queryClient.invalidateQueries({ queryKey: calendarRangeQueryRootKey });
    },
  });

  async function loadSchedulePage(signal?: AbortSignal) {
    if (!session?.authenticated) {
      return;
    }

    try {
      const loadSequence = ++loadSequenceRef.current;
      setStatus("loading");
      const [week, suggestions, mutationPreflight] = await Promise.all([
        api.getWeekSchedule(signal),
        api.getSuggestions(signal),
        api.getScheduleMutationPreflight(signal),
      ]);

      if (signal?.aborted || loadSequence !== loadSequenceRef.current) {
        return;
      }
      setBlockingConflictMessage(
        mutationPreflight.pending
          ? mutationPreflight.message ?? "확인이 필요한 일정 충돌이 있습니다. 먼저 충돌 내용을 확인해 주세요."
          : null,
      );
      const currentPageSuggestions = suggestions.data.filter((suggestion) =>
        isSuggestionInCurrentPageSession(
          suggestion,
          aiConversationStartedAtRef.current,
          aiConversationSuggestionIdsRef.current,
        ),
      );
      setData({
        week,
        suggestions: currentPageSuggestions,
      });
      setStatus("ready");
      setError(null);
    } catch (loadError) {
      if (isAbortError(loadError)) {
        return;
      }
      if (signal?.aborted) {
        return;
      }
      setStatus("error");
      setError(loadError instanceof Error ? loadError.message : "페이지를 불러오지 못했습니다.");
    }
  }

  useEffect(() => {
    if (!session?.authenticated) {
      return;
    }
    if (selectedDateTimeZoneRef.current === scheduleTimeZone) {
      return;
    }
    selectedDateTimeZoneRef.current = scheduleTimeZone;
    setSelectedDateKey(zonedDateKey(new Date(), scheduleTimeZone));
  }, [scheduleTimeZone, session?.authenticated]);

  useEffect(() => {
    if (!session?.authenticated) {
      return;
    }

    const controller = new AbortController();
    void loadSchedulePage(controller.signal);
    return () => controller.abort();
  }, [session?.authenticated]);

  useEffect(() => {
    if (!data.suggestions.length) {
      return;
    }
    if (!shouldStickToAiThreadBottomRef.current) {
      return;
    }
    aiThreadEndRef.current?.scrollIntoView({ block: "end" });
  }, [data.suggestions]);

  useBodyScrollLock(isCreateModalOpen || Boolean(editingOccurrence) || Boolean(blockingConflictMessage));


  useEffect(() => {
    if (!isCreateModalOpen) {
      return;
    }

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setCreateModalOpen(false);
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    activityFieldRef.current?.focus();

    return () => {
      window.removeEventListener("keydown", handleKeyDown);
    };
  }, [isCreateModalOpen]);

  async function ensureNoPendingScheduleConflict() {
    try {
      const mutationPreflight = await api.getScheduleMutationPreflight();
      if (!mutationPreflight.pending) {
        return true;
      }
      setBlockingConflictMessage(
        mutationPreflight.message ?? "확인이 필요한 일정 충돌이 있습니다. 먼저 충돌 내용을 확인해 주세요.",
      );
      return false;
    } catch (preflightError) {
      showNotice({
        tone: "error",
        title: "일정 충돌 확인에 실패했습니다.",
        detail: preflightError instanceof Error ? preflightError.message : "잠시 후 다시 시도하면 됩니다.",
      });
      return false;
    }
  }

  async function openCreateModal() {
    if (!(await ensureNoPendingScheduleConflict())) {
      return;
    }
    setEditingBlock(null);
    setForm(DEFAULT_FORM);
    setFormConflictDetail(null);
    setCreateModalOpen(true);
  }

  function focusChangeRequest() {
    requestInputRef.current?.scrollIntoView({ behavior: "smooth", block: "center" });
    requestInputRef.current?.focus();
  }

  async function openEditModal(block: EditableScheduleBlock) {
    if (!(await ensureNoPendingScheduleConflict())) {
      return;
    }
    setEditingBlock(block);
    setFormConflictDetail(null);
    setForm({
      dayOfWeek: block.dayOfWeek.toUpperCase(),
      startTime: block.startTime,
      endTime: block.endTime,
      activity: block.activity,
      category: block.category,
      note: block.note ?? "",
    });
    setCreateModalOpen(true);
  }


  async function openOccurrenceEditModal(occurrence: CalendarOccurrence) {
    if (occurrence.entityType === "ROUTINE_BLOCK") {
      showNotice({
        tone: "error",
        title: "보호 루틴은 여기서 직접 수정할 수 없습니다.",
        detail: "루틴 블록은 주간 스택의 로컬 블록 편집에서 조정하세요.",
      });
      return;
    }
    if (occurrence.entityType !== "EVENT" && occurrence.entityType !== "TASK") {
      showNotice({
        tone: "error",
        title: "편집할 수 없는 항목입니다.",
      });
      return;
    }
    if (!(await ensureNoPendingScheduleConflict())) {
      return;
    }

    try {
      setIsMutating(true);
      const timeZone = safeTimeZone(session?.timezone);
      if (occurrence.entityType === "EVENT") {
        const response = await api.getEvent(occurrence.entityId);
        const event = response.data;
        setOccurrenceForm({
          title: event.title,
          description: event.description ?? "",
          startAt: zonedDateTimeInputValue(event.startAt, timeZone),
          endAt: zonedDateTimeInputValue(event.endAt, timeZone),
          dueDate: "",
          estimatedMinutes: "30",
          priority: String(event.priority ?? 3),
          category: event.category ?? occurrence.category ?? "WORK",
          goalId: event.goalId,
        });
      } else {
        const response = await api.getTask(occurrence.entityId);
        const task = response.data;
        setOccurrenceForm({
          title: task.title,
          description: task.description ?? "",
          startAt: "",
          endAt: "",
          dueDate: zonedDateTimeInputValue(task.dueDate, timeZone),
          estimatedMinutes: String(task.estimatedMinutes ?? 30),
          priority: String(task.priority ?? 3),
          category: task.category ?? occurrence.category ?? "WORK",
          goalId: task.goalId,
        });
      }
      setEditingOccurrence(occurrence);
    } catch (openError) {
      showNotice({
        tone: "error",
        title: "원본 항목을 불러오지 못했습니다.",
        detail: mutationErrorDetail(openError),
      });
    } finally {
      setIsMutating(false);
    }
  }

  function handleOccurrenceFormChange(
    event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>,
  ) {
    const { name, value } = event.target;
    setOccurrenceForm((current) => ({
      ...current,
      [name]: value,
    }));
  }

  async function handleSaveOccurrence(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!editingOccurrence || isMutating || scheduleMutationActiveRef.current) {
      return;
    }
    if (!(await ensureNoPendingScheduleConflict())) {
      return;
    }

    try {
      scheduleMutationActiveRef.current = true;
      setIsMutating(true);
      const timeZone = safeTimeZone(session?.timezone);
      const priority = Number(occurrenceForm.priority);
      if (!Number.isFinite(priority) || priority < 1 || priority > 5) {
        throw new Error("우선순위는 1부터 5 사이여야 합니다.");
      }

      if (editingOccurrence.entityType === "EVENT") {
        const startAt = isoFromZonedDateTimeInput(occurrenceForm.startAt, timeZone);
        const endAt = isoFromZonedDateTimeInput(occurrenceForm.endAt, timeZone);
        if (new Date(endAt).getTime() <= new Date(startAt).getTime()) {
          throw new Error("종료 시각은 시작 시각보다 늦어야 합니다.");
        }
        await runScheduleMutationExclusive((signal) =>
          api.updateEvent(editingOccurrence.entityId, {
            title: occurrenceForm.title,
            description: occurrenceForm.description || null,
            startAt,
            endAt,
            priority,
            category: occurrenceForm.category,
            goalId: occurrenceForm.goalId,
          }, signal),
        );
      } else if (editingOccurrence.entityType === "TASK") {
        const estimatedMinutes = Number(occurrenceForm.estimatedMinutes);
        if (!Number.isFinite(estimatedMinutes) || estimatedMinutes < 0) {
          throw new Error("예상 소요 시간은 0분 이상이어야 합니다.");
        }
        await runScheduleMutationExclusive((signal) =>
          api.updateTask(editingOccurrence.entityId, {
            title: occurrenceForm.title,
            description: occurrenceForm.description || null,
            dueDate: occurrenceForm.dueDate ? isoFromZonedDateTimeInput(occurrenceForm.dueDate, timeZone) : null,
            estimatedMinutes,
            priority,
            goalId: occurrenceForm.goalId,
            category: occurrenceForm.category || null,
          }, signal),
        );
      }

      setEditingOccurrence(null);
      setOccurrenceForm(DEFAULT_OCCURRENCE_FORM);
      showNotice({
        tone: "success",
        title: "캘린더 원본 항목을 수정했습니다.",
      });
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: calendarRangeQueryRootKey }),
        loadSchedulePage(),
        refreshSession({ silent: true }),
      ]);
    } catch (saveError) {
      if (isConflictError(saveError)) {
        setBlockingConflictMessage(saveError.message);
        await loadSchedulePage();
      }
      showNotice({
        tone: "error",
        title: "캘린더 원본 항목 수정에 실패했습니다.",
        detail: mutationErrorDetail(saveError),
      });
    } finally {
      scheduleMutationActiveRef.current = false;
      setIsMutating(false);
    }
  }

  function handleFormChange(
    event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement | HTMLSelectElement>,
  ) {
    const { name, value } = event.target;
    setFormConflictDetail(null);
    setForm((current) => ({
      ...current,
      [name]: value,
    }));
  }

  async function handleSaveBlock(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isMutating || scheduleMutationActiveRef.current) {
      return;
    }

    try {
      scheduleMutationActiveRef.current = true;
      setIsMutating(true);
      const isEditingExistingBlock = Boolean(editingBlock);
      const request = {
        dayOfWeek: form.dayOfWeek,
        startTime: form.startTime,
        endTime: form.endTime,
        activity: form.activity,
        category: form.category,
        note: form.note,
      };

      if (editingBlock) {
        await runScheduleMutationExclusive((signal) =>
          updateBlockMutation.mutateAsync({
            blockId: editingBlock.id,
            request,
            signal,
          }),
        );
      } else {
        await runScheduleMutationExclusive((signal) => createBlockMutation.mutateAsync({ request, signal }));
      }

      setForm(DEFAULT_FORM);
      setEditingBlock(null);
      setFormConflictDetail(null);
      setCreateModalOpen(false);
      showNotice({
        tone: "success",
        title: isEditingExistingBlock ? "일정 블록을 수정했습니다." : "새 일정 블록을 추가했습니다.",
      });
      if (isEditingExistingBlock) {
        await refreshSession({ silent: true });
      } else {
        await Promise.all([loadSchedulePage(), refreshSession({ silent: true })]);
      }
    } catch (saveError) {
      if (isConflictError(saveError)) {
        setBlockingConflictMessage(saveError.message);
        setFormConflictDetail(
          "방금 저장은 적용하지 않았습니다. 입력한 내용은 그대로 남아 있습니다. 최신 일정표와 비교한 뒤 다시 저장하세요.",
        );
        await loadSchedulePage();
      }
      showNotice({
        tone: "error",
        title: editingBlock ? "일정 블록 수정에 실패했습니다." : "일정 블록 추가에 실패했습니다.",
        detail: mutationErrorDetail(saveError),
      });
    } finally {
      scheduleMutationActiveRef.current = false;
      setIsMutating(false);
    }
  }

  async function handleDeleteBlock(block: EditableScheduleBlock) {
    if (isMutating || scheduleMutationActiveRef.current) {
      return;
    }

    try {
      scheduleMutationActiveRef.current = true;
      setIsMutating(true);
      await runScheduleMutationExclusive((signal) => deleteBlockMutation.mutateAsync({ blockId: block.id, signal }));
      setEditingBlock(null);
      setDeleteCandidate(null);
      setForm(DEFAULT_FORM);
      setFormConflictDetail(null);
      setCreateModalOpen(false);
      showNotice({
        tone: "success",
        title: "일정 블록을 삭제했습니다.",
      });
      await refreshSession({ silent: true });
    } catch (deleteError) {
      if (isConflictError(deleteError)) {
        setBlockingConflictMessage(deleteError.message);
        await loadSchedulePage();
      }
      showNotice({
        tone: "error",
        title: "일정 블록 삭제에 실패했습니다.",
        detail: isConflictError(deleteError)
          ? "이미 다른 화면에서 변경된 일정입니다. 최신 일정표를 다시 불러왔으니 항목을 확인한 뒤 다시 삭제하세요."
          : mutationErrorDetail(deleteError),
      });
    } finally {
      scheduleMutationActiveRef.current = false;
      setIsMutating(false);
    }
  }

  async function handleRequestSuggestion(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();
    if (isMutating) {
      return;
    }

    const trimmedReason = requestReason.trim();
    if (!trimmedReason) {
      showNotice({
        tone: "error",
        title: "요청 내용이 필요합니다.",
      });
      return;
    }

    try {
      setIsMutating(true);
      const response = await api.requestManualReschedule(trimmedReason);
      aiConversationSuggestionIdsRef.current.add(response.data.id);
      setRequestReason("");
      showNotice({
        tone: "success",
        title: "변경 요청을 만들었습니다.",
      });
      await Promise.all([loadSchedulePage(), refreshSession({ silent: true })]);
    } catch (requestError) {
      showNotice({
        tone: "error",
        title: "변경 요청에 실패했습니다.",
        detail:
          requestError instanceof Error ? requestError.message : "잠시 후 다시 시도하면 됩니다.",
      });
    } finally {
      setIsMutating(false);
    }
  }

  async function handleSuggestionDecision(action: "apply" | "reject", suggestionId: string) {
    if (isMutating) {
      return;
    }
    const suggestion = data.suggestions.find((item) => item.id === suggestionId);
    if (action === "apply" && !suggestion?.executable) {
      const display = suggestion ? getSuggestionDisplayState(suggestion) : null;
      showNotice({
        tone: "error",
        title: display?.title ?? "적용할 변경이 없습니다.",
        detail: display?.detail,
      });
      return;
    }

    try {
      setIsMutating(true);
      const response = action === "apply"
        ? await api.applySuggestion(suggestionId)
        : await api.rejectSuggestion(suggestionId);

      showNotice({
        tone: "success",
        title: action === "apply" ? "변경을 일정표에 반영했습니다." : "변경을 보류했습니다.",
        detail: getSuggestionNoticeDetail(response),
      });
      await Promise.all([loadSchedulePage(), refreshSession({ silent: true })]);
    } catch (decisionError) {
      if (isConflictError(decisionError)) {
        await loadSchedulePage();
      }
      showNotice({
        tone: "error",
        title: "변경 처리에 실패했습니다.",
        detail: isConflictError(decisionError)
          ? "제안이 이미 다른 화면에서 처리되었거나 일정이 바뀌었습니다. 최신 변경 요청을 다시 확인하세요."
          : decisionError instanceof Error
            ? decisionError.message
            : "잠시 후 다시 시도하면 됩니다.",
      });
    } finally {
      setIsMutating(false);
    }
  }


  const pendingSuggestions = data.suggestions.filter((suggestion) => suggestion.status === "pending");
  const pendingDraftItems = useMemo(
    () => buildAiDraftProjectionItems(pendingSuggestions, scheduleTimeZone),
    [pendingSuggestions, scheduleTimeZone],
  );
  const conversationSuggestions = [...data.suggestions.slice(0, 8)].reverse();
  const aiRequestRail = (
    <section className="ai-compose-card ai-chat-card" data-testid="schedule-ai-right-rail" aria-label="일정 변경 요청 대화">
      <div className="ai-chat-head">
        <div>
          <p className="panel-kicker">일정 변경 요청</p>
          <h2>변경 요청</h2>
        </div>
        <span className="accent-pill" data-testid="schedule-pending-count">
          {pendingSuggestions.length ? `${pendingSuggestions.length}건 대기` : "대기 없음"}
        </span>
      </div>

      <div
        ref={aiThreadRef}
        className="ai-chat-thread"
        aria-label="일정 변경 요청 대화"
        aria-live="polite"
        role="log"
        onScroll={(event) => {
          const element = event.currentTarget;
          shouldStickToAiThreadBottomRef.current = element.scrollHeight - element.clientHeight - element.scrollTop <= 48;
        }}
      >
        {conversationSuggestions.length ? (
          conversationSuggestions.map((suggestion) => {
            const display = getSuggestionDisplayState(suggestion);
            const isPendingSuggestion = suggestion.status === "pending";
            return (
              <div className="ai-chat-turn" key={suggestion.id}>
                <div className="chat-bubble user">
                  <span>요청</span>
                  <p data-user-content="true">{formatServiceCopy(suggestion.reason || suggestion.summary)}</p>
                </div>
                <div className={`chat-bubble assistant ${display.kind}`}>
                  <span>답변</span>
                  <SuggestionReviewCard
                    className="ai-suggestion-card suggestion-diff-card chat-suggestion-card"
                    isPending={isMutating}
                    suggestion={suggestion}
                    kicker={isPendingSuggestion ? "검토" : suggestion.statusLabel}
                    readOnly={!isPendingSuggestion}
                    onApply={() => void handleSuggestionDecision("apply", suggestion.id)}
                    onReject={() => void handleSuggestionDecision("reject", suggestion.id)}
                  />
                </div>
              </div>
            );
          })
        ) : (
          <div className="chat-empty-state">
            <strong>아직 대화가 없습니다.</strong>
            <p>아래 입력창에 “내일 오전 회의 준비 시간을 비워줘”처럼 요청하면 답변이 이어집니다.</p>
          </div>
        )}
        <div ref={aiThreadEndRef} aria-hidden="true" />
      </div>

      <form className="ai-compose-form ai-chat-input" onSubmit={(event) => void handleRequestSuggestion(event)}>
        <textarea
          ref={requestInputRef}
          className="ai-compose-textarea"
          data-testid="schedule-ai-request-input"
          value={requestReason}
          onChange={(event) => setRequestReason(event.target.value)}
          placeholder="예: 내일 오전 회의 준비 시간을 비워줘"
          rows={3}
        />
        <div className="ai-compose-actions">
          <button
            className="ghost-btn"
            data-testid="schedule-add-button"
            disabled={isMutating}
            type="button"
            onClick={() => void openCreateModal()}
          >
            일정 직접 추가
          </button>
          <button className="solid-btn" data-testid="schedule-ai-request-submit" disabled={isMutating} type="submit">
            요청 보내기
          </button>
        </div>
      </form>
    </section>
  );

  return (
    <AppShell
      eyebrow="이번 주"
      title="주간 일정"
      description="이번 주 시간을 한 화면에 보여주고 바로 조정할 수 있게 합니다."
      rightRail={aiRequestRail}
      showTopBar={false}
    >
      {!data.week && status === "loading" ? (
        <section className="surface-card empty-state">
          <strong>주간 일정을 불러오는 중입니다.</strong>
          <p>일정표를 준비합니다.</p>
        </section>
      ) : null}

      {!data.week && status === "error" ? (
        <section className="surface-card empty-state">
          <strong>주간 일정을 불러오지 못했습니다.</strong>
          <p>{error ?? "잠시 후 다시 시도하면 됩니다."}</p>
          <button className="solid-btn" data-testid="status-retry-action" onClick={() => void loadSchedulePage()}>
            다시 불러오기
          </button>
        </section>
      ) : null}

      {data.week ? (
        <section className="planner-layout schedule-layout">
          <article className="planner-board schedule-main-board">
            <div className="schedule-view-toolbar" aria-label="일정 보기 전환">
              <div>
                <p className="panel-kicker">보기</p>
                <h2>{SCHEDULE_TIMELINE_VIEW_DESCRIPTIONS[timelineView]}</h2>
              </div>
              <div className="schedule-view-switcher" role="group" aria-label="타임라인 보기">
                {SCHEDULE_TIMELINE_VIEWS.map((view) => {
                  const isActive = timelineView === view;
                  return (
                    <button
                      aria-pressed={isActive}
                      className={`schedule-view-option ${isActive ? "active" : ""}`}
                      data-testid={`schedule-view-option-${view}`}
                      key={view}
                      type="button"
                      onClick={() => setTimelineView(view)}
                    >
                      <span>{SCHEDULE_TIMELINE_VIEW_LABELS[view]}</span>
                      <small>{SCHEDULE_TIMELINE_VIEW_DESCRIPTIONS[view]}</small>
                    </button>
                  );
                })}
              </div>
            </div>

            <AiDraftProjection draftItems={pendingDraftItems} />
            <div className="schedule-mobile-action-strip" aria-label="주간 일정 빠른 작업">
              <button
                className="ghost-btn"
                data-testid="schedule-mobile-add-button"
                disabled={isMutating}
                type="button"
                onClick={() => void openCreateModal()}
              >
                일정 직접 추가
              </button>
              <button
                className="solid-btn"
                data-testid="schedule-mobile-change-request"
                disabled={isMutating}
                type="button"
                onClick={focusChangeRequest}
              >
                변경 요청
              </button>
            </div>

            <div className="schedule-device-layout">
              <section
                className="schedule-calendar-panel"
                aria-label={`${SCHEDULE_TIMELINE_VIEW_DESCRIPTIONS[timelineView]} 일정`}
                data-active-view={timelineView}
              >
                {timelineView === "month" ? (
                  <MonthlyMosaic
                    range={monthRangeQuery.data}
                    isLoading={monthRangeQuery.isLoading || monthRangeQuery.isFetching}
                    isError={monthRangeQuery.isError}
                    selectedDateKey={selectedDateKey}
                    monthDateKey={monthRangeWindow.startDateKey}
                    timeZone={monthRangeWindow.timeZone}
                    onSelectDate={(dateKey) => {
                      setSelectedDateKey(dateKey);
                      setTimelineView("day");
                    }}
                  />
                ) : null}
                {timelineView === "week" ? (
                  <WeeklyStack
                    week={data.week}
                    onBlockSelect={(block) => void openEditModal(block)}
                    timeZone={session?.timezone}
                  />
                ) : null}
                {timelineView === "day" ? (
                  <SelectedDayTimeline
                    week={data.week}
                    range={monthRangeQuery.data}
                    isRangeLoading={monthRangeQuery.isLoading || monthRangeQuery.isFetching}
                    isRangeError={monthRangeQuery.isError}
                    selectedDateKey={selectedDateKey}
                    draftItems={pendingDraftItems}
                    onBlockSelect={(block) => void openEditModal(block)}
                    onOccurrenceSelect={(occurrence) => void openOccurrenceEditModal(occurrence)}
                    onReturnToAgenda={() => setTimelineView("agenda")}
                    onReturnToMonth={() => setTimelineView("month")}
                    timeZone={monthRangeWindow.timeZone}
                  />
                ) : null}
                {timelineView === "agenda" ? (
                  <AgendaStream
                    range={agendaRangeQuery.data}
                    isLoading={agendaRangeQuery.isLoading || agendaRangeQuery.isFetching}
                    isError={agendaRangeQuery.isError}
                    timeZone={agendaRangeWindow.timeZone}
                    onSelectDate={(dateKey) => {
                      setSelectedDateKey(dateKey);
                      setTimelineView("day");
                    }}
                    onOccurrenceSelect={(occurrence) => void openOccurrenceEditModal(occurrence)}
                  />
                ) : null}
              </section>
            </div>
          </article>
        </section>
      ) : null}


      {editingOccurrence ? (
        <div
          className="modal-backdrop"
          role="presentation"
          onClick={() => {
            setEditingOccurrence(null);
            setOccurrenceForm(DEFAULT_OCCURRENCE_FORM);
          }}
        >
          <div
            className="modal-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby="calendar-occurrence-edit-title"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="modal-header">
              <div>
                <p className="panel-kicker">Range Spine 원본 편집</p>
                <h2 id="calendar-occurrence-edit-title">
                  {editingOccurrence.entityType === "EVENT" ? "캘린더 이벤트 수정" : "할 일 수정"}
                </h2>
              </div>
              <button
                className="ghost-btn secondary-action-btn"
                type="button"
                onClick={() => {
                  setEditingOccurrence(null);
                  setOccurrenceForm(DEFAULT_OCCURRENCE_FORM);
                }}
              >
                닫기
              </button>
            </div>

            <form className="modal-form" onSubmit={handleSaveOccurrence}>
              <div className="modal-form-grid">
                <div className="field modal-form-span-2">
                  <label htmlFor="occurrence-title">제목</label>
                  <input
                    id="occurrence-title"
                    name="title"
                    type="text"
                    value={occurrenceForm.title}
                    onChange={handleOccurrenceFormChange}
                    required
                  />
                </div>
                {editingOccurrence.entityType === "EVENT" ? (
                  <>
                    <div className="field">
                      <label htmlFor="occurrence-startAt">시작</label>
                      <input
                        id="occurrence-startAt"
                        name="startAt"
                        type="datetime-local"
                        value={occurrenceForm.startAt}
                        onChange={handleOccurrenceFormChange}
                        required
                      />
                    </div>
                    <div className="field">
                      <label htmlFor="occurrence-endAt">종료</label>
                      <input
                        id="occurrence-endAt"
                        name="endAt"
                        type="datetime-local"
                        value={occurrenceForm.endAt}
                        onChange={handleOccurrenceFormChange}
                        required
                      />
                    </div>
                  </>
                ) : (
                  <>
                    <div className="field">
                      <label htmlFor="occurrence-dueDate">마감</label>
                      <input
                        id="occurrence-dueDate"
                        name="dueDate"
                        type="datetime-local"
                        value={occurrenceForm.dueDate}
                        onChange={handleOccurrenceFormChange}
                      />
                    </div>
                    <div className="field">
                      <label htmlFor="occurrence-estimatedMinutes">예상 분</label>
                      <input
                        id="occurrence-estimatedMinutes"
                        name="estimatedMinutes"
                        type="number"
                        min="0"
                        value={occurrenceForm.estimatedMinutes}
                        onChange={handleOccurrenceFormChange}
                      />
                    </div>
                  </>
                )}
                <div className="field">
                  <label htmlFor="occurrence-priority">우선순위</label>
                  <input
                    id="occurrence-priority"
                    name="priority"
                    type="number"
                    min="1"
                    max="5"
                    value={occurrenceForm.priority}
                    onChange={handleOccurrenceFormChange}
                  />
                </div>
                <div className="field">
                  <label htmlFor="occurrence-category">카테고리</label>
                  <select
                    id="occurrence-category"
                    name="category"
                    value={occurrenceForm.category}
                    onChange={handleOccurrenceFormChange}
                  >
                    {Object.keys(CATEGORY_LABELS).map((category) => (
                      <option key={category} value={category}>
                        {CATEGORY_LABELS[category] ?? category}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="field modal-form-span-2">
                  <label htmlFor="occurrence-description">설명</label>
                  <textarea
                    id="occurrence-description"
                    name="description"
                    value={occurrenceForm.description}
                    onChange={handleOccurrenceFormChange}
                    rows={3}
                  />
                </div>
              </div>

              <p className="modal-support-copy">
                이 편집은 /api/calendar/range에 투영되는 EVENT/TASK 원본을 직접 갱신합니다. 루틴 보호 블록은 주간 스택에서 따로 수정합니다.
              </p>
              <div className="modal-actions">
                <button
                  className="ghost-btn"
                  type="button"
                  disabled={isMutating}
                  onClick={() => {
                    setEditingOccurrence(null);
                    setOccurrenceForm(DEFAULT_OCCURRENCE_FORM);
                  }}
                >
                  취소
                </button>
                <button className="solid-btn" disabled={isMutating} type="submit">
                  원본 저장
                </button>
              </div>
            </form>
          </div>
        </div>
      ) : null}

      {isCreateModalOpen ? (
        <div
          className="modal-backdrop"
          role="presentation"
          onClick={() => {
            setFormConflictDetail(null);
            setCreateModalOpen(false);
          }}
        >
          <div
            className="modal-panel"
            role="dialog"
            aria-modal="true"
            aria-labelledby="create-block-title"
            onClick={(event) => event.stopPropagation()}
          >
            <div className="modal-header">
              <div>
                <p className="panel-kicker">{editingBlock ? "블록 수정" : "직접 추가"}</p>
                <h2 id="create-block-title">{editingBlock ? "일정 블록 수정" : "새 블록 추가"}</h2>
              </div>
              <button
                className="ghost-btn secondary-action-btn"
                type="button"
                onClick={() => {
                  setFormConflictDetail(null);
                  setCreateModalOpen(false);
                }}
              >
                닫기
              </button>
            </div>

            <form className="modal-form" onSubmit={handleSaveBlock}>
              {formConflictDetail ? (
                <div className="inline-message error conflict-resolution-message" role="alert">
                  <strong>서버의 일정이 먼저 바뀌었습니다.</strong>
                  <p>{formConflictDetail}</p>
                </div>
              ) : null}
              <div className="modal-form-grid">
                <div className="field">
                  <label htmlFor="dayOfWeek">요일</label>
                  <select
                    id="dayOfWeek"
                    name="dayOfWeek"
                    value={form.dayOfWeek}
                    onChange={handleFormChange}
                  >
                    {DAY_ORDER.map((day) => (
                      <option key={day} value={day.toUpperCase()}>
                        {DAY_FULL_LABELS[day]}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="field">
                  <label htmlFor="startTime">시작</label>
                  <input
                    id="startTime"
                    name="startTime"
                    type="time"
                    value={form.startTime}
                    onChange={handleFormChange}
                  />
                </div>
                <div className="field">
                  <label htmlFor="endTime">종료</label>
                  <input
                    id="endTime"
                    name="endTime"
                    type="time"
                    value={form.endTime}
                    onChange={handleFormChange}
                  />
                </div>
                <div className="field modal-form-span-2">
                  <label htmlFor="activity">활동</label>
                  <input
                    id="activity"
                    name="activity"
                    type="text"
                    ref={activityFieldRef}
                    value={form.activity}
                    onChange={handleFormChange}
                    placeholder="예: 집중 업무 시간"
                    required
                  />
                </div>
                <div className="field">
                  <label htmlFor="category">카테고리</label>
                  <select
                    id="category"
                    name="category"
                    value={form.category}
                    onChange={handleFormChange}
                  >
                    {Object.keys(CATEGORY_LABELS).map((category) => (
                      <option key={category} value={category}>
                        {CATEGORY_LABELS[category] ?? category}
                      </option>
                    ))}
                  </select>
                </div>
                <div className="field modal-form-span-2">
                  <label htmlFor="note">메모</label>
                  <input
                    id="note"
                    name="note"
                    type="text"
                    value={form.note}
                    onChange={handleFormChange}
                    placeholder="선택 사항"
                  />
                </div>
              </div>

              <div className="modal-actions">
                {editingBlock ? (
                  <button
                    className="ghost-btn danger-btn"
                    type="button"
                    disabled={isMutating}
                    onClick={() => setDeleteCandidate(editingBlock)}
                  >
                    삭제
                  </button>
                ) : null}
                <button
                  className="ghost-btn"
                  type="button"
                  disabled={isMutating}
                  onClick={() => {
                    setFormConflictDetail(null);
                    setCreateModalOpen(false);
                  }}
                >
                  취소
                </button>
                <button className="solid-btn" disabled={isMutating} type="submit">
                  {editingBlock ? "변경 저장" : "블록 추가"}
                </button>
              </div>
            </form>
          </div>
        </div>
      ) : null}

      {blockingConflictMessage ? (
        <div className="modal-backdrop" role="presentation">
          <div
            className="modal-panel conflict-blocking-modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="schedule-conflict-title"
          >
            <div className="modal-header">
              <div>
                <p className="panel-kicker">충돌 확인</p>
                <h2 id="schedule-conflict-title">일정 변경을 잠시 멈췄습니다.</h2>
              </div>
            </div>
            <div className="inline-message error conflict-resolution-message" role="alert">
              <strong>확인이 필요한 일정 충돌이 있습니다.</strong>
              <p>{blockingConflictMessage}</p>
            </div>
            <p className="modal-support-copy">
              최신 일정과 충돌 내용을 먼저 확인한 뒤 다시 저장하면 됩니다. 입력 중인 내용은 닫기 전까지 유지됩니다.
            </p>
            <div className="modal-actions">
              <button
                className="solid-btn"
                data-testid="schedule-conflict-blocking-dismiss"
                type="button"
                onClick={() => setBlockingConflictMessage(null)}
              >
                확인
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {deleteCandidate ? (
        <ConfirmDialog
          title="일정 블록을 삭제할까요?"
          description={`"${deleteCandidate.activity}" 블록을 삭제합니다. 삭제한 블록은 되돌릴 수 없습니다.`}
          confirmLabel="삭제"
          isPending={isMutating}
          onCancel={() => setDeleteCandidate(null)}
          onConfirm={() => void handleDeleteBlock(deleteCandidate)}
        />
      ) : null}
    </AppShell>
  );
}
