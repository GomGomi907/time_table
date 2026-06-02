"use client";

import { ChangeEvent, FormEvent, useEffect, useRef, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { QueryKey } from "@tanstack/react-query";

import { AppShell } from "@/components/app-shell";
import { ConfirmDialog } from "@/components/confirm-dialog";
import { SuggestionReviewCard } from "@/components/suggestion-review-card";
import { calendarRangeQueryRootKey } from "@/hooks/use-calendar-range-query";
import { api, isConflictError } from "@/lib/api";
import { useBodyScrollLock } from "@/lib/use-body-scroll-lock";
import {
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
const SCHEDULE_MUTATION_TIMEOUT_MS = 10_000;

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
  const [deleteCandidate, setDeleteCandidate] = useState<EditableScheduleBlock | null>(null);
  const [isMutating, setIsMutating] = useState(false);
  const activityFieldRef = useRef<HTMLInputElement | null>(null);
  const requestInputRef = useRef<HTMLTextAreaElement | null>(null);
  const loadSequenceRef = useRef(0);
  const scheduleMutationQueueRef = useRef<Promise<void>>(Promise.resolve());
  const scheduleMutationActiveRef = useRef(false);
  const queryClient = useQueryClient();

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
      if (mutationPreflight.pending) {
        setBlockingConflictMessage(
          mutationPreflight.message ?? "확인이 필요한 일정 충돌이 있습니다. 먼저 충돌 내용을 확인해 주세요.",
        );
      }
      setData({
        week,
        suggestions: suggestions.data,
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

    const controller = new AbortController();
    void loadSchedulePage(controller.signal);
    return () => controller.abort();
  }, [session?.authenticated]);

  useBodyScrollLock(isCreateModalOpen || Boolean(blockingConflictMessage));


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
      await api.requestManualReschedule(trimmedReason);
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
  const conversationSuggestions = data.suggestions.slice(0, 8);
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

      <div className="ai-chat-thread" aria-live="polite">
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
              <section className="schedule-calendar-panel" aria-label="반응형 주간 일정">
                <WeeklyStack
                  week={data.week}
                  onBlockSelect={(block) => void openEditModal(block)}
                  timeZone={session?.timezone}
                />
              </section>
            </div>
          </article>
        </section>
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
