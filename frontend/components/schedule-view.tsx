"use client";

import { ChangeEvent, FormEvent, useEffect, useRef, useState } from "react";

import { AppShell } from "@/components/app-shell";
import { SectionHeader } from "@/components/section-header";
import { api } from "@/lib/api";
import { formatAiActionLabel, formatAiPreviewDetail, formatClockValue, formatServiceCopy } from "@/lib/format";
import {
  dismissOnboardingDayHandoffHint,
  OnboardingDayHandoff,
  readOnboardingDayHandoff,
} from "@/lib/onboarding-day-handoff";
import {
  CATEGORY_LABELS,
  DAY_FULL_LABELS,
  DAY_ORDER,
  DEFAULT_WEEK_VIEW_END_MINUTES,
  DEFAULT_WEEK_VIEW_START_MINUTES,
  PIXELS_PER_MINUTE,
  durationInMinutes,
  getCurrentDayName,
  getCurrentMinutes,
  getDailyBlocks,
  getLaidOutBlocks,
  isBlockActive,
  minutesFromClock,
} from "@/lib/schedule";
import { RescheduleSuggestion, ScheduleBlock, WeekScheduleResponse } from "@/lib/types";
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

interface ScheduleData {
  week: WeekScheduleResponse | null;
  suggestions: RescheduleSuggestion[];
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

function isSleepBlock(block: ScheduleBlock) {
  return block.category === "SLEEP";
}

function getWeekBlocks(week: WeekScheduleResponse | null) {
  return DAY_ORDER.flatMap((day) =>
    getDailyBlocks(week, day).map((block) => ({
      ...block,
      dayOfWeek: day,
    })),
  );
}

function dedupeBlocks<T extends ScheduleBlock & { dayOfWeek?: string }>(blocks: T[]) {
  const seen = new Set<string>();
  return blocks.filter((block) => {
    const key = [
      block.dayOfWeek ?? "",
      block.startTime,
      block.endTime,
      block.activity.trim(),
      block.category,
      block.note?.trim() ?? "",
    ].join("|");
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

function shouldShowInWeeklyGrid(block: ScheduleBlock) {
  return block.category !== "SLEEP";
}

function getVisibleWeekBlocks(week: WeekScheduleResponse | null) {
  return dedupeBlocks(getWeekBlocks(week).filter(shouldShowInWeeklyGrid));
}

function getVisibleDailyBlocks(week: WeekScheduleResponse | null, dayOfWeek: string) {
  return dedupeBlocks(getDailyBlocks(week, dayOfWeek).filter(shouldShowInWeeklyGrid));
}

function formatDurationLabel(totalMinutes: number) {
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;

  if (hours === 0) {
    return `${minutes}분`;
  }

  return minutes === 0 ? `${hours}시간` : `${hours}시간 ${minutes}분`;
}

function formatHourLabel(hour: number) {
  return `${String(((hour % 24) + 24) % 24).padStart(2, "0")}:00`;
}

function formatMinuteBoundary(minutes: number) {
  const hour = Math.floor(minutes / 60);
  const minute = minutes % 60;
  const label = `${String(((hour % 24) + 24) % 24).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
  return hour >= 24 ? `다음 ${label}` : label;
}

function formatRangeBoundary(hour: number) {
  return hour >= 24 ? `다음 ${formatHourLabel(hour)}` : formatHourLabel(hour);
}

function countPreviewGroups(items: RescheduleSuggestion["previewItems"]) {
  return new Set(items.map((item) => `${item.actionType}:${item.title}`)).size;
}

function formatSuggestionPermission(suggestion: RescheduleSuggestion) {
  if (!suggestion.executable || suggestion.executableCommandCount === 0) {
    return "검토용 제안입니다. 적용해도 바뀔 일정 항목이 없습니다.";
  }

  return `적용 전에는 앱 일정과 Google 캘린더와 할 일을 바꾸지 않습니다. 승인하면 ${countPreviewGroups(suggestion.previewItems)}개 조정 묶음을 앱에 먼저 저장하고 Google 반영은 권한 상태에 맞춰 처리합니다.`;
}

function formatSuggestionEvidence(reason: string | null, actionType: string) {
  if (reason?.trim()) {
    return reason;
  }

  if (actionType === "SHIFT_BLOCK") {
    return "충돌 가능 시간과 보호 시간을 함께 보고 이동 후보를 골랐습니다.";
  }
  if (actionType === "CREATE_BLOCK") {
    return "비어 있는 시간에 실행 가능한 새 블록을 확보합니다.";
  }
  if (actionType === "UPDATE_BLOCK") {
    return "기존 블록을 현재 요청에 맞게 조정합니다.";
  }

  return "요청한 내용과 이번 주 일정을 함께 확인했습니다.";
}

function buildPreviewDigest(items: RescheduleSuggestion["previewItems"], limit = 4) {
  const groups = new Map<
    string,
    RescheduleSuggestion["previewItems"][number] & { groupedCount: number }
  >();

  for (const item of items) {
    const key = `${item.actionType}:${item.title}`;
    const existing = groups.get(key);
    if (existing) {
      existing.groupedCount += 1;
      continue;
    }

    groups.set(key, {
      ...item,
      groupedCount: 1,
    });
  }

  return Array.from(groups.values()).slice(0, limit).map((item) => ({
    ...item,
    title: item.groupedCount > 1 ? `${item.title} · ${item.groupedCount}일 묶음` : item.title,
    detail:
      item.groupedCount > 1 && item.detail
        ? `${item.detail} · 반복 루틴을 한 번에 정리`
        : item.detail,
  }));
}

function normalizeAfterWake(minutes: number, wakeMinutes: number) {
  return minutes < wakeMinutes ? minutes + 24 * 60 : minutes;
}

function getSleepBoundedRange(week: WeekScheduleResponse | null) {
  const wakeCandidates: number[] = [];
  const bedtimeCandidates: number[] = [];

  for (const block of getWeekBlocks(week)) {
    const start = minutesFromClock(block.startTime);
    const end = minutesFromClock(block.endTime);

    if (isSleepBlock(block)) {
      bedtimeCandidates.push(start < 12 * 60 ? start + 24 * 60 : start);

      if (end <= start) {
        wakeCandidates.push(end);
      }
      continue;
    }

    if (block.activity.includes("기상")) {
      wakeCandidates.push(start);
    }
  }

  if (!wakeCandidates.length || !bedtimeCandidates.length) {
    return null;
  }

  const startMinutes = Math.floor(Math.min(...wakeCandidates) / 60) * 60;
  const normalizedBedtimes = bedtimeCandidates.map((minutes) =>
    minutes < startMinutes ? minutes + 24 * 60 : minutes,
  );
  const endMinutes = Math.ceil(Math.max(...normalizedBedtimes) / 60) * 60;

  if (endMinutes <= startMinutes) {
    return null;
  }

  return {
    startMinutes,
    endMinutes,
  };
}

function expandRangeToMinimum(
  range: { startMinutes: number; endMinutes: number },
  bounds: { startMinutes: number; endMinutes: number },
  minimumMinutes = 8 * 60,
) {
  if (range.endMinutes - range.startMinutes >= minimumMinutes) {
    return range;
  }

  const midpoint = Math.round((range.startMinutes + range.endMinutes) / 2);
  const halfMinimum = Math.floor(minimumMinutes / 2);
  const boundedStart = Math.max(bounds.startMinutes, midpoint - halfMinimum);
  const boundedEnd = Math.min(bounds.endMinutes, boundedStart + minimumMinutes);
  const startMinutes = Math.max(bounds.startMinutes, boundedEnd - minimumMinutes);

  return {
    startMinutes,
    endMinutes: boundedEnd,
  };
}

function getWeekViewRange(
  week: WeekScheduleResponse | null,
  currentMinutes?: number,
) {
  const sleepRange = getSleepBoundedRange(week);

  if (sleepRange) {
    const visiblePoints = getVisibleWeekBlocks(week).flatMap((block) => {
      const start = normalizeAfterWake(minutesFromClock(block.startTime), sleepRange.startMinutes);
      return [start, start + durationInMinutes(block.startTime, block.endTime)];
    });
    const normalizedCurrent =
      currentMinutes === undefined ? null : normalizeAfterWake(currentMinutes, sleepRange.startMinutes);

    if (!visiblePoints.length) {
      return sleepRange;
    }

    const focusPoints =
      normalizedCurrent !== null &&
      normalizedCurrent >= sleepRange.startMinutes &&
      normalizedCurrent <= sleepRange.endMinutes
        ? [...visiblePoints, normalizedCurrent]
        : visiblePoints;
    const startMinutes = Math.floor(Math.max(sleepRange.startMinutes, Math.min(...focusPoints) - 60) / 60) * 60;
    const endMinutes = Math.ceil(Math.min(sleepRange.endMinutes, Math.max(...focusPoints) + 60) / 60) * 60;

    return expandRangeToMinimum(
      { startMinutes, endMinutes },
      sleepRange,
    );
  }

  const points = getVisibleWeekBlocks(week).flatMap((block) => {
    const start = minutesFromClock(block.startTime);
    const end = start + durationInMinutes(block.startTime, block.endTime);
    return [start, end];
  });

  if (!points.length) {
    return {
      startMinutes: DEFAULT_WEEK_VIEW_START_MINUTES,
      endMinutes: DEFAULT_WEEK_VIEW_END_MINUTES,
    };
  }

  const currentPoint = currentMinutes === undefined ? [] : [currentMinutes];
  const focusPoints = [...points, ...currentPoint];
  const earliest = Math.min(...focusPoints);
  const latest = Math.max(...focusPoints);
  const startHour = Math.max(0, Math.min(8, Math.floor((earliest - 30) / 60)));
  const endHour = Math.min(26, Math.max(20, Math.ceil((latest + 30) / 60)));

  return {
    startMinutes: startHour * 60,
    endMinutes: Math.max(endHour * 60, startHour * 60 + 10 * 60),
  };
}

function WeeklyGrid({
  week,
  pendingSuggestionCount,
  onBlockSelect,
  timeZone,
}: {
  week: WeekScheduleResponse | null;
  pendingSuggestionCount: number;
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
  const currentScheduleBlock = todayBlocks.find((block) => isBlockActive(block, currentMinutes)) ?? null;
  const viewRange = getWeekViewRange(week, currentMinutes);
  const currentTimelineMinutes = normalizeAfterWake(currentMinutes, viewRange.startMinutes);
  const trackHeight = (viewRange.endMinutes - viewRange.startMinutes) * PIXELS_PER_MINUTE;
  const startHour = Math.floor(viewRange.startMinutes / 60);
  const endHour = Math.ceil(viewRange.endMinutes / 60);
  const timeTicks = Array.from({ length: endHour - startHour + 1 }, (_, index) => startHour + index);
  const showNowLine =
    currentTimelineMinutes >= viewRange.startMinutes && currentTimelineMinutes <= viewRange.endMinutes;
  const mobileDays = [currentDay, ...DAY_ORDER.filter((day) => day !== currentDay)];

  return (
    <>
      <div className="mobile-week-agenda" aria-label="모바일 주간 일정 목록">
        <section className="mobile-schedule-brief">
          <p className="panel-kicker">오늘 먼저 볼 것</p>
          <div className="mobile-brief-grid">
            <span>
              <strong>{todayBlocks.length}</strong>
              오늘 일정
            </span>
            <span>
              <strong>{pendingSuggestionCount}</strong>
              확인할 조정안
            </span>
            <span>
              <strong>{busyDays}</strong>
              활동 요일
            </span>
          </div>
          <p>
            전체 주간표는 접어 두고, 오늘 일정과 조정 영향부터 확인합니다.
          </p>
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
                    <button
                      key={block.id}
                      type="button"
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
                      {block.note ? <span>{formatServiceCopy(block.note)}</span> : null}
                    </button>
                  ))}
                  {blocks.length > previewBlocks.length ? (
                    <p className="mobile-empty-day">
                      나머지 {blocks.length - previewBlocks.length}개는 필요할 때만 펼쳐 확인합니다.
                    </p>
                  ) : null}
                </div>
              ) : (
                <p className="mobile-empty-day">
                  빈 시간입니다. 직접 추가하거나 조정안을 요청할 수 있습니다.
                </p>
              )}
            </>
          );

          return (
            <section className={`mobile-day-card ${isToday ? "today" : ""}`} key={day}>
              {isToday ? (
                <>
                  <div className="mobile-day-head">
                    <strong>오늘 · {DAY_FULL_LABELS[day]}</strong>
                    <span>{blocks.length ? `${blocks.length}개 일정` : "비어 있음"}</span>
                  </div>
                  {cardContent}
                </>
              ) : (
                <details className="mobile-day-details">
                  <summary className="mobile-day-head">
                    <strong>{DAY_FULL_LABELS[day]}</strong>
                    <span>{blocks.length ? `${blocks.length}개 일정 보기` : "비어 있음"}</span>
                  </summary>
                  {cardContent}
                </details>
              )}
            </section>
          );
        })}
      </div>

      <div className="week-grid-shell">
        <div className="week-planner-head">
          <div>
            <p className="panel-kicker">주간 일정</p>
            <h2>이번 주 시간 배치</h2>
            <p>
              수면은 접어 두고, 지금 확인할 시간대와 실제 일정 카드를 먼저 보여줍니다.
            </p>
          </div>
          <div className="week-summary-strip" aria-label="주간 일정 요약">
            <span>
              <strong>{totalBlocks}</strong>
              표시 일정
            </span>
            <span>
              <strong>{formatDurationLabel(totalMinutes)}</strong>
              깨어있는 배치
            </span>
            <span>
              <strong>{busyDays}</strong>
              활동 요일
            </span>
          </div>
        </div>
        {currentScheduleBlock ? (
          <button
            className={`current-schedule-card ${categoryTone(currentScheduleBlock.category)}`}
            type="button"
            onClick={() =>
              onBlockSelect({
                ...currentScheduleBlock,
                dayOfWeek: currentDay,
              })
            }
          >
            <span className="current-schedule-kicker">지금 일정</span>
            <strong>{formatServiceCopy(currentScheduleBlock.activity)}</strong>
            <span className="current-schedule-time">
              {formatClockValue(currentScheduleBlock.startTime)} - {formatClockValue(currentScheduleBlock.endTime)}
            </span>
            {currentScheduleBlock.note ? <span className="current-schedule-note">{formatServiceCopy(currentScheduleBlock.note)}</span> : null}
          </button>
        ) : (
          <div className="current-schedule-card empty" aria-label="현재 진행 중인 일정 없음">
            <span className="current-schedule-kicker">지금 일정</span>
            <strong>진행 중인 일정이 없습니다.</strong>
            <span className="current-schedule-time">아래 일정표에서 다음 일정을 확인해보세요.</span>
          </div>
        )}
        <div className="week-grid-affordance" aria-hidden="true">
          <span>← 좌우로 밀어 다른 요일 보기 →</span>
        </div>
        <div className="week-grid-scroll">
          <div className="week-grid-header">
            <div className="week-grid-corner">
              <strong>시간</strong>
              <span>
                {formatMinuteBoundary(viewRange.startMinutes)}–{formatMinuteBoundary(viewRange.endMinutes)}
              </span>
            </div>
            {DAY_ORDER.map((day) => {
              const blocks = getVisibleDailyBlocks(week, day);
              const isToday = currentDay === day;
              return (
                <div
                  key={day}
                  className={`week-grid-day-head ${isToday ? "today" : ""} ${blocks.length === 0 ? "empty" : ""}`}
                >
                  <strong>{DAY_FULL_LABELS[day]}</strong>
                  <small>{blocks.length ? `${blocks.length}개 일정` : "비어 있음"}</small>
                </div>
              );
            })}
          </div>

          <div className="week-grid-body">
            <div className="schedule-time-axis" style={{ height: `${trackHeight}px` }}>
              {timeTicks.map((hour, index) => (
                <div
                  key={hour}
                  className={`schedule-time-tick ${index === 0 ? "start" : ""} ${hour === endHour ? "end" : ""}`}
                  style={{ top: `${(hour * 60 - viewRange.startMinutes) * PIXELS_PER_MINUTE}px` }}
                >
                  {formatRangeBoundary(hour)}
                </div>
              ))}
            </div>

          {DAY_ORDER.map((day) => {
            const blocks = getVisibleDailyBlocks(week, day);
            const laidOutBlocks = getLaidOutBlocks(blocks, viewRange.startMinutes, viewRange.endMinutes);
            return (
              <div
                key={day}
                className={`schedule-day-column ${currentDay === day ? "today" : ""}`}
                style={{ height: `${trackHeight}px` }}
              >
                {currentDay === day && showNowLine ? (
                  <div
                    className="schedule-now-line"
                    style={{ top: `${(currentTimelineMinutes - viewRange.startMinutes) * PIXELS_PER_MINUTE}px` }}
                  >
                    <span>지금</span>
                  </div>
                ) : null}

                {laidOutBlocks.map((block) => {
                  const isCurrentBlock = day === currentDay && isBlockActive(block, currentMinutes);
                  return (
                    <button
                      key={block.id}
                      type="button"
                      aria-label={`${DAY_FULL_LABELS[day]} ${formatClockValue(block.startTime)}부터 ${formatClockValue(block.endTime)}까지 ${formatServiceCopy(block.activity)} 수정`}
                      className={`schedule-block ${categoryTone(block.category)} ${block.isCompact ? "compact" : ""} ${block.isTight ? "tight" : ""} ${isCurrentBlock ? "current" : ""}`}
                      style={{
                        top: block.top,
                        height: block.height,
                        left: block.left,
                        width: block.width,
                      }}
                      onClick={() =>
                        onBlockSelect({
                          ...block,
                          dayOfWeek: day,
                        })
                      }
                    >
                      {isCurrentBlock ? <span className="current-event-chip">지금 일정</span> : null}
                      <strong>{formatServiceCopy(block.activity)}</strong>
                      <span className="event-time">
                        {formatClockValue(block.startTime)} - {formatClockValue(block.endTime)}
                      </span>
                      {block.note ? <p className="event-note">{formatServiceCopy(block.note)}</p> : null}
                    </button>
                  );
                })}
              </div>
            );
          })}
        </div>
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
  const [onboardingHandoff, setOnboardingHandoff] = useState<OnboardingDayHandoff | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState<string | null>(null);
  const [form, setForm] = useState(DEFAULT_FORM);
  const [requestReason, setRequestReason] = useState("");
  const [isCreateModalOpen, setCreateModalOpen] = useState(false);
  const [editingBlock, setEditingBlock] = useState<EditableScheduleBlock | null>(null);
  const [isMutating, setIsMutating] = useState(false);
  const activityFieldRef = useRef<HTMLInputElement | null>(null);

  async function loadSchedulePage() {
    if (!session?.authenticated) {
      return;
    }

    try {
      setStatus("loading");
      const [week, suggestions] = await Promise.all([
        api.getWeekSchedule(),
        api.getSuggestions(),
      ]);

      setData({
        week,
        suggestions: suggestions.data,
      });
      setStatus("ready");
      setError(null);
    } catch (loadError) {
      setStatus("error");
      setError(loadError instanceof Error ? loadError.message : "페이지를 불러오지 못했습니다.");
    }
  }

  useEffect(() => {
    if (!session?.authenticated) {
      return;
    }

    void loadSchedulePage();
  }, [session?.authenticated]);

  useEffect(() => {
    if (!session?.userId) {
      setOnboardingHandoff(null);
      return;
    }

    setOnboardingHandoff(readOnboardingDayHandoff(session.userId));
  }, [session?.userId]);

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

  function openCreateModal() {
    setEditingBlock(null);
    setForm(DEFAULT_FORM);
    setCreateModalOpen(true);
  }

  function openEditModal(block: EditableScheduleBlock) {
    setEditingBlock(block);
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
    setForm((current) => ({
      ...current,
      [name]: value,
    }));
  }

  async function handleSaveBlock(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (isMutating) {
      return;
    }

    try {
      setIsMutating(true);
      const request = {
        dayOfWeek: form.dayOfWeek,
        startTime: form.startTime,
        endTime: form.endTime,
        activity: form.activity,
        category: form.category,
        note: form.note,
      };

      if (editingBlock) {
        await api.updateScheduleBlock(editingBlock.id, request);
      } else {
        await api.createScheduleBlock(request);
      }

      setForm(DEFAULT_FORM);
      setEditingBlock(null);
      setCreateModalOpen(false);
      showNotice({
        tone: "success",
        title: editingBlock ? "일정 블록을 수정했습니다." : "새 일정 블록을 추가했습니다.",
      });
      await Promise.all([loadSchedulePage(), refreshSession({ silent: true })]);
    } catch (saveError) {
      showNotice({
        tone: "error",
        title: editingBlock ? "일정 블록 수정에 실패했습니다." : "일정 블록 추가에 실패했습니다.",
        detail: saveError instanceof Error ? saveError.message : "입력값을 다시 확인해 주세요.",
      });
    } finally {
      setIsMutating(false);
    }
  }

  async function handleDeleteBlock() {
    if (!editingBlock || isMutating) {
      return;
    }

    const confirmed = window.confirm(`"${editingBlock.activity}" 블록을 삭제할까요?`);
    if (!confirmed) {
      return;
    }

    try {
      setIsMutating(true);
      await api.deleteScheduleBlock(editingBlock.id);
      setEditingBlock(null);
      setForm(DEFAULT_FORM);
      setCreateModalOpen(false);
      showNotice({
        tone: "success",
        title: "일정 블록을 삭제했습니다.",
      });
      await Promise.all([loadSchedulePage(), refreshSession({ silent: true })]);
    } catch (deleteError) {
      showNotice({
        tone: "error",
        title: "일정 블록 삭제에 실패했습니다.",
        detail: deleteError instanceof Error ? deleteError.message : "잠시 후 다시 시도해 주세요.",
      });
    } finally {
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
        title: "요청 내용을 먼저 적어 주세요.",
      });
      return;
    }

    try {
      setIsMutating(true);
      await api.requestManualReschedule(trimmedReason);
      setRequestReason("");
      showNotice({
        tone: "success",
        title: "일정 조정 요청을 만들었습니다.",
      });
      await Promise.all([loadSchedulePage(), refreshSession({ silent: true })]);
    } catch (requestError) {
      showNotice({
        tone: "error",
        title: "일정 조정 요청에 실패했습니다.",
        detail:
          requestError instanceof Error ? requestError.message : "잠시 후 다시 시도해 주세요.",
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
      showNotice({
        tone: "error",
        title: "적용할 변경이 없는 조정안입니다.",
      });
      return;
    }

    try {
      setIsMutating(true);
      if (action === "apply") {
        await api.applySuggestion(suggestionId);
      } else {
        await api.rejectSuggestion(suggestionId);
      }

      showNotice({
        tone: "success",
        title: action === "apply" ? "조정안을 일정표에 반영했습니다." : "조정안을 보류했습니다.",
      });
      await Promise.all([loadSchedulePage(), refreshSession({ silent: true })]);
    } catch (decisionError) {
      showNotice({
        tone: "error",
        title: "조정안 처리에 실패했습니다.",
        detail:
          decisionError instanceof Error
            ? decisionError.message
            : "잠시 후 다시 시도해 주세요.",
      });
    } finally {
      setIsMutating(false);
    }
  }

  function handleDismissOnboardingHint() {
    dismissOnboardingDayHandoffHint(session?.userId, "schedule");
    setOnboardingHandoff(readOnboardingDayHandoff(session?.userId));
  }

  const pendingSuggestions = data.suggestions.filter((suggestion) => suggestion.status === "pending");

  return (
    <AppShell
      eyebrow="이번 주 확인"
      title="주간 일정"
      description="이번 주 일정과 겹치는 시간을 확인해보세요. 바꿀 내용은 조정안으로 요청할 수 있습니다."
      screenQuestion="겹치거나 빡빡한 시간은 어디인가요?"
      primaryActionLabel={pendingSuggestions.length ? "조정안 확인" : "일정 추가 또는 조정 요청"}
      actions={
        <button className="ghost-btn" onClick={() => void loadSchedulePage()}>
          새로고침
        </button>
      }
    >
      {!data.week && status === "loading" ? (
        <section className="surface-card empty-state">
          <strong>주간 일정을 불러오는 중입니다.</strong>
          <p>일정표와 확인 대기 중인 조정안을 함께 준비하고 있습니다.</p>
        </section>
      ) : null}

      {!data.week && status === "error" ? (
        <section className="surface-card empty-state">
          <strong>주간 일정을 불러오지 못했습니다.</strong>
          <p>{error ?? "서비스 연결을 다시 확인해 주세요."}</p>
        </section>
      ) : null}

      {data.week ? (
      <section className="planner-layout schedule-layout">
        <article className="planner-board schedule-main-board">
          <SectionHeader
            eyebrow="일정 조정"
            title="이번 주 일정표"
            trailing={
              <div className="board-legend">
                <span>
                  <i className="dot coral" />
                  집중
                </span>
                <span>
                  <i className="dot sand" />
                  루틴
                </span>
                <span>
                  <i className="dot slate" />
                  기타
                </span>
              </div>
            }
          />

          {onboardingHandoff && !onboardingHandoff.scheduleDismissed ? (
            <section className="onboarding-day-coachmark">
              <div className="onboarding-day-coachmark-copy">
                <p className="panel-kicker">첫날 안내</p>
                <strong>
                  {onboardingHandoff.appliedSuggestion
                    ? "처음 설정에서 만든 첫 조정안을 여기서 바로 이어서 다듬을 수 있습니다."
                    : "처음 설정에서 저장한 생활 리듬이 일정 조정의 기본 기준이 됩니다."}
                </strong>
                <p>
                  {onboardingHandoff.suggestionSummary ??
                    "필요하면 아래 입력창에 일정 이동이나 집중 시간 추가 요청을 문장으로 적어 보세요."}
                </p>
                <div className="onboarding-day-answer-list">
                  {onboardingHandoff.answers.slice(0, 3).map((answer) => (
                    <div key={answer.id} className="onboarding-day-chip">
                      <strong>{answer.title}</strong>
                      <span>{answer.value}</span>
                    </div>
                  ))}
                </div>
              </div>

              <button
                className="ghost-btn secondary-action-btn"
                onClick={handleDismissOnboardingHint}
                type="button"
              >
                닫기
              </button>
            </section>
          ) : null}

          <WeeklyGrid
            week={data.week}
            pendingSuggestionCount={pendingSuggestions.length}
            onBlockSelect={openEditModal}
            timeZone={session?.timezone}
          />

          <section className="ai-compose-card">
            <SectionHeader
              eyebrow="조정 요청"
              title="일정 조정 요청"
              description="바꾸고 싶은 일정과 지켜야 할 시간을 적어 주세요. 조정안은 승인하면 반영합니다."
            />

            <form className="ai-compose-form" onSubmit={(event) => void handleRequestSuggestion(event)}>
              <textarea
                className="ai-compose-textarea"
                value={requestReason}
                onChange={(event) => setRequestReason(event.target.value)}
                placeholder="예: 화요일 저녁 운동을 30분 뒤로 미루고, 금요일 오전엔 집중 업무 2시간을 확보해 줘"
                rows={2}
              />
              <div className="ai-compose-actions">
                <button
                  className="ghost-btn"
                  disabled={isMutating}
                  type="button"
                  onClick={openCreateModal}
                >
                  일정 직접 추가
                </button>
                <button className="solid-btn" disabled={isMutating} type="submit">
                  요청 보내기
                </button>
              </div>
            </form>

            {pendingSuggestions.length ? (
              <div className="ai-suggestion-strip">
                {pendingSuggestions.slice(0, 2).map((suggestion) => (
                  <div className="ai-suggestion-card suggestion-diff-card" key={suggestion.id}>
                    <div className="suggestion-diff-head">
                      <span className="accent-pill subtle">{suggestion.statusLabel}</span>
                      <strong>{formatServiceCopy(suggestion.summary)}</strong>
                      <p>{formatServiceCopy(suggestion.reason ?? suggestion.explanation)}</p>
                    </div>
                    <div className="suggestion-summary-strip" aria-label="조정안 핵심 요약">
                      <span>
                        <b>{countPreviewGroups(suggestion.previewItems)}개</b>
                        변경 묶음
                      </span>
                      <span>
                        <b>승인 전 안전</b>
                        대기 중
                      </span>
                      <span>
                        <b>Google 반영</b>
                        권한 상태 확인 후
                      </span>
                    </div>
                    {suggestion.previewItems.length ? (
                      <div className="suggestion-diff-list" aria-label="조정안 변경 미리보기">
                        {buildPreviewDigest(suggestion.previewItems, 1).map((item, index) => (
                          <div
                            className="suggestion-diff-row"
                            key={`${suggestion.id}-${index}-${item.actionType}-${item.targetId ?? item.title}`}
                          >
                            <span className="diff-before">
                              <b>대상</b>
                              {formatServiceCopy(item.title)}
                            </span>
                            <span className="diff-after">
                              <b>{formatAiActionLabel(item.actionType)}</b>
                              {formatAiPreviewDetail(item.detail) ?? formatServiceCopy(item.reason) ?? "세부 변경을 확인해보세요."}
                            </span>
                            <span className="diff-impact">
                              <b>근거</b>
                              {formatServiceCopy(formatSuggestionEvidence(item.reason, item.actionType))}
                            </span>
                          </div>
                        ))}
                      </div>
                    ) : null}
                    <p className="micro-copy suggestion-permission-copy">
                      밤 루틴과 겹치는 조정은 적용 시 대체·이동 대상까지 함께 확인합니다.
                    </p>
                    <p className="micro-copy suggestion-permission-copy">
                      {formatSuggestionPermission(suggestion)}
                    </p>
                    <div className="suggestion-actions">
                      <button
                        className="ghost-btn"
                        type="button"
                        disabled={isMutating}
                        onClick={() => void handleSuggestionDecision("reject", suggestion.id)}
                      >
                        보류
                      </button>
                      <button
                        className="solid-btn"
                        type="button"
                        disabled={isMutating || !suggestion.executable}
                        onClick={() => void handleSuggestionDecision("apply", suggestion.id)}
                      >
                        적용
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            ) : null}
          </section>
        </article>
      </section>
      ) : null}

      {isCreateModalOpen ? (
        <div
          className="modal-backdrop"
          role="presentation"
          onClick={() => setCreateModalOpen(false)}
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
                onClick={() => setCreateModalOpen(false)}
              >
                닫기
              </button>
            </div>

            <form className="modal-form" onSubmit={handleSaveBlock}>
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
                    onClick={() => void handleDeleteBlock()}
                  >
                    삭제
                  </button>
                ) : null}
                <button
                  className="ghost-btn"
                  type="button"
                  disabled={isMutating}
                  onClick={() => setCreateModalOpen(false)}
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
    </AppShell>
  );
}
