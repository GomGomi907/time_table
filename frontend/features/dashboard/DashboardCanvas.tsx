"use client";

import { motion } from "framer-motion";
import {
  ArrowRight,
  CalendarRange,
  Clock3,
  Crosshair,
  Link2,
  ListTodo,
  RefreshCw,
  Target,
} from "lucide-react";
import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import { PageIntro } from "@/components/app/PageIntro";
import { WorkspaceStat } from "@/components/app/WorkspaceStat";
import {
  AiSuggestionsWidget,
  type WorkspaceSuggestion,
} from "@/components/widgets/AiSuggestionsWidget";
import { GoalsOverviewWidget } from "@/components/widgets/GoalsOverviewWidget";
import { SyncStatusWidget } from "@/components/widgets/SyncStatusWidget";
import { TasksWidget } from "@/components/widgets/TasksWidget";
import { useTasksResource } from "@/components/widgets/useTasksResource";
import { useGoals } from "@/features/goals/useGoals";
import { useWeekSchedule } from "@/features/schedule/useWeekSchedule";
import {
  CATEGORY_LABELS,
  CATEGORY_TONES,
  DAY_FULL_LABELS,
  DAY_LABELS,
  DAY_ORDER,
  findDaySchedule,
  formatNowLabel,
  formatTimeRange,
  getCurrentBlock,
  getNextBlock,
  getProgressPercent,
  getRemainingLabel,
  getTodayDayKey,
  sortBlocks,
  toMinutes,
} from "@/features/schedule/utils";
import type {
  AuthSessionResponse,
  GoalResponse,
  ScheduleBlockResponse,
  WeekScheduleResponse,
} from "@/shared/api/types";

interface DashboardCanvasProps {
  session: AuthSessionResponse;
}

const BOARD_START_MINUTES = 6 * 60;
const BOARD_END_MINUTES = 24 * 60;
const PIXELS_PER_MINUTE = 0.78;
const BOARD_HEIGHT = (BOARD_END_MINUTES - BOARD_START_MINUTES) * PIXELS_PER_MINUTE;

const GOAL_CATEGORY_LABELS: Record<GoalResponse["category"], string> = {
  HEALTH: "건강",
  CAREER: "커리어",
  FINANCE: "재정",
  GROWTH: "성장",
  HOBBY: "취미",
  OTHER: "기타",
};

const formatCountLabel = (value: number, singular: string) => `${value}${singular}`;

const formatMinutesLabel = (minutes: number) => {
  if (minutes <= 0) {
    return "지금 바로";
  }

  const safeMinutes = Math.max(0, Math.round(minutes));
  const hours = Math.floor(safeMinutes / 60);
  const remainder = safeMinutes % 60;

  if (hours > 0) {
    return `${hours}시간 ${remainder}분`;
  }

  return `${remainder}분`;
};

const formatAbsoluteLabel = (value: string | null) => {
  if (!value) {
    return "기록 없음";
  }

  const parsed = new Date(value);

  if (Number.isNaN(parsed.getTime())) {
    return "확인 필요";
  }

  return new Intl.DateTimeFormat("ko-KR", {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(parsed);
};

const getVisibleBlockPlacement = (block: ScheduleBlockResponse) => {
  const start = toMinutes(block.startTime);
  let end = toMinutes(block.endTime);

  if (end <= start) {
    end += 1440;
  }

  const visibleStart = Math.max(start, BOARD_START_MINUTES);
  const visibleEnd = Math.min(end, BOARD_END_MINUTES);

  if (visibleEnd <= visibleStart) {
    return null;
  }

  return {
    top: (visibleStart - BOARD_START_MINUTES) * PIXELS_PER_MINUTE,
    height: Math.max((visibleEnd - visibleStart) * PIXELS_PER_MINUTE, 42),
  };
};

const getTodaySummary = (
  todayBlocks: ScheduleBlockResponse[],
  now: Date,
  currentBlock: ScheduleBlockResponse | undefined
) => {
  if (currentBlock) {
    return getRemainingLabel(currentBlock, now);
  }

  const nextBlock = getNextBlock(todayBlocks, now);

  if (!nextBlock) {
    return "남은 일정 없음";
  }

  const nowMinutes = now.getHours() * 60 + now.getMinutes();
  const gapMinutes = Math.max(0, toMinutes(nextBlock.startTime) - nowMinutes);

  return `${formatMinutesLabel(gapMinutes)} 뒤 시작`;
};

const buildSuggestions = ({
  session,
  now,
  currentBlock,
  nextBlock,
  goals,
  pendingTasks,
  dueSoonCount,
  unassignedCount,
}: {
  session: AuthSessionResponse;
  now: Date;
  currentBlock: ScheduleBlockResponse | undefined;
  nextBlock: ScheduleBlockResponse | undefined;
  goals: GoalResponse[];
  pendingTasks: Array<{ title: string }>;
  dueSoonCount: number;
  unassignedCount: number;
}): WorkspaceSuggestion[] => {
  const suggestions: WorkspaceSuggestion[] = [];

  if (!session.lastSyncAt) {
    suggestions.push({
      id: "sync-first",
      title: "첫 동기화를 점검해 두세요",
      detail:
        "Google Calendar와 Tasks 연결 상태를 확인해 두면 이번 주 시간표와 할 일 초기 반영이 쉬워집니다.",
      tone: "accent",
      href: "/settings",
      actionLabel: "연동 설정 보기",
    });
  }

  if (dueSoonCount > 0) {
    suggestions.push({
      id: "due-soon",
      title: `마감 임박 할 일 ${dueSoonCount}개`,
      detail:
        "현재 일정이 끝난 뒤 가장 가까운 마감 항목을 먼저 처리하도록 순서를 조정하는 편이 안전합니다.",
      tone: "danger",
      href: "/focus",
      actionLabel: "집중 화면 열기",
    });
  }

  if (!currentBlock && nextBlock && unassignedCount > 0) {
    const gapMinutes = Math.max(
      0,
      toMinutes(nextBlock.startTime) - (now.getHours() * 60 + now.getMinutes())
    );

    suggestions.push({
      id: "open-gap",
      title: `${formatMinutesLabel(gapMinutes)} 빈 시간이 생겼습니다`,
      detail:
        pendingTasks[0]
          ? `"${pendingTasks[0].title}" 같은 미배치 할 일을 지금 슬롯에 넣어두면 실행 전환이 자연스럽습니다.`
          : "미배치 할 일을 먼저 배치해 두면 다음 일정 전까지의 공백을 줄일 수 있습니다.",
      tone: "success",
      href: "/schedule",
      actionLabel: "시간표에서 배치하기",
    });
  }

  if (currentBlock && nextBlock) {
    const currentEnd = toMinutes(currentBlock.endTime);
    const nextStart = toMinutes(nextBlock.startTime);
    const bufferMinutes = Math.max(0, nextStart - currentEnd);

    if (bufferMinutes <= 10) {
      suggestions.push({
        id: "tight-buffer",
        title: "다음 일정과 버퍼가 촘촘합니다",
        detail:
          "현재 블록이 조금만 밀려도 다음 일정과 바로 충돌할 수 있습니다. 종료 체크를 미리 준비해 두는 편이 좋습니다.",
        tone: "accent",
        href: "/focus",
        actionLabel: "실행 모드로 전환",
      });
    }
  }

  const stalledGoals = goals.filter((goal) => goal.status === "FAILED");

  if (stalledGoals.length > 0) {
    suggestions.push({
      id: "goal-risk",
      title: `위험 상태 목표 ${stalledGoals.length}개`,
      detail: `${stalledGoals
        .slice(0, 2)
        .map((goal) => goal.title)
        .join(", ")} 흐름을 이번 주 보드에서 다시 배치해 보세요.`,
      tone: "danger",
      href: "/goals",
      actionLabel: "목표 보드 보기",
    });
  }

  if (suggestions.length === 0) {
    suggestions.push({
      id: "steady",
      title: "현재 흐름은 안정적입니다",
      detail:
        "큰 충돌 징후는 없습니다. 현재 일정 종료 시점에 맞춰 다음 블록 준비만 확인하면 됩니다.",
      tone: "default",
      href: "/focus",
      actionLabel: "실행 화면 보기",
    });
  }

  return suggestions.slice(0, 3);
};

const CurrentFocusPanel = ({
  now,
  currentBlock,
  nextBlock,
  activeGoals,
}: {
  now: Date;
  currentBlock: ScheduleBlockResponse | undefined;
  nextBlock: ScheduleBlockResponse | undefined;
  activeGoals: GoalResponse[];
}) => {
  const currentGoal = activeGoals[0];

  return (
    <section className="surface-card p-6 sm:p-8">
      <div className="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
        <div>
          <p className="metric-label">실행 허브</p>
          <h2 className="mt-2 text-2xl font-bold tracking-tight text-[var(--foreground)]">
            현재 집중 상태
          </h2>
          <p className="mt-2 max-w-2xl text-sm leading-6 text-[var(--foreground-muted)]">
            통합 화면에서 지금 해야 할 일과 다음 전환 시점을 함께 확인합니다.
          </p>
        </div>

        <Link href="/focus" className="btn-primary">
          <Crosshair className="h-4 w-4" />
          집중 화면 열기
        </Link>
      </div>

      {currentBlock ? (
        <div className="mt-6 grid gap-4 lg:grid-cols-[minmax(0,1fr)_280px]">
          <article className="rounded-[1.7rem] border border-[rgba(245,92,18,0.18)] bg-[linear-gradient(135deg,rgba(245,92,18,0.14),var(--surface-raised))] p-5">
            <div className="flex flex-wrap items-center gap-2">
              <span className="status-badge">진행 중</span>
              <span className="status-badge">
                {CATEGORY_LABELS[currentBlock.category]}
              </span>
              <span className="status-badge">
                {formatTimeRange(currentBlock.startTime, currentBlock.endTime)}
              </span>
            </div>
            <h3 className="mt-4 text-3xl font-extrabold tracking-tight text-[var(--foreground)]">
              {currentBlock.activity}
            </h3>
            <p className="mt-2 text-base text-[var(--foreground-muted)]">
              {currentBlock.note ?? "실행 중 메모가 아직 없습니다."}
            </p>

            <div className="mt-6 space-y-2">
              <div className="flex items-center justify-between gap-3 text-sm">
                <span className="font-semibold text-[var(--foreground)]">
                  {getRemainingLabel(currentBlock, now)}
                </span>
                <span className="text-[var(--accent)]">
                  {Math.round(getProgressPercent(currentBlock, now))}%
                </span>
              </div>
              <div className="h-2 rounded-full bg-[var(--surface-100)]">
                <div
                  className="h-full rounded-full bg-[var(--accent)] transition-all duration-500"
                  style={{ width: `${getProgressPercent(currentBlock, now)}%` }}
                />
              </div>
            </div>
          </article>

          <article className="rounded-[1.7rem] border border-[var(--border)] bg-[var(--surface-raised)] p-5">
            <p className="metric-label">다음 전환</p>
            <h3 className="mt-3 text-xl font-bold text-[var(--foreground)]">
              {nextBlock ? nextBlock.activity : "남은 일정 없음"}
            </h3>
            <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">
              {nextBlock
                ? `${formatTimeRange(nextBlock.startTime, nextBlock.endTime)} · ${CATEGORY_LABELS[nextBlock.category]}`
                : "오늘 계획한 블록이 모두 끝났습니다."}
            </p>

            <div className="mt-5 space-y-3">
              <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface-muted)] p-4">
                <p className="metric-label">관련 목표</p>
                <p className="mt-2 text-sm font-semibold text-[var(--foreground)]">
                  {currentGoal
                    ? `${currentGoal.title} · ${GOAL_CATEGORY_LABELS[currentGoal.category]}`
                    : "연결된 목표 정보 없음"}
                </p>
              </div>
              <div className="rounded-2xl border border-[var(--border)] bg-[var(--surface-muted)] p-4">
                <p className="metric-label">현재 시각</p>
                <p className="mt-2 text-sm font-semibold text-[var(--foreground)]">
                  {formatNowLabel(now)}
                </p>
              </div>
            </div>
          </article>
        </div>
      ) : (
        <div className="mt-6 grid gap-4 lg:grid-cols-[minmax(0,1fr)_280px]">
          <article className="rounded-[1.7rem] border border-[rgba(33,122,87,0.18)] bg-[linear-gradient(135deg,rgba(33,122,87,0.12),var(--surface-raised))] p-5">
            <div className="flex flex-wrap items-center gap-2">
              <span className="status-badge">빈 시간</span>
              {nextBlock ? (
                <span className="status-badge">
                  다음 일정 {formatTimeRange(nextBlock.startTime, nextBlock.endTime)}
                </span>
              ) : (
                <span className="status-badge">오늘 일정 종료</span>
              )}
            </div>
            <h3 className="mt-4 text-3xl font-extrabold tracking-tight text-[var(--foreground)]">
              지금은 전환 여유 구간입니다
            </h3>
            <p className="mt-2 text-base text-[var(--foreground-muted)]">
              {nextBlock
                ? "추천 태스크를 넣거나 다음 블록 준비를 먼저 끝내 두면 흐름이 끊기지 않습니다."
                : "내일을 위한 정리 시간이나 회고 슬롯으로 활용해도 좋습니다."}
            </p>
          </article>

          <article className="rounded-[1.7rem] border border-[var(--border)] bg-[var(--surface-raised)] p-5">
            <p className="metric-label">다음 일정</p>
            <h3 className="mt-3 text-xl font-bold text-[var(--foreground)]">
              {nextBlock ? nextBlock.activity : "예정 없음"}
            </h3>
            <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">
              {nextBlock
                ? `${formatTimeRange(nextBlock.startTime, nextBlock.endTime)} · ${CATEGORY_LABELS[nextBlock.category]}`
                : "새 블록을 넣으면 이곳에서 즉시 이어집니다."}
            </p>
          </article>
        </div>
      )}
    </section>
  );
};

const WeeklyScheduleBoard = ({
  schedule,
  todayKey,
  now,
  currentBlock,
}: {
  schedule: WeekScheduleResponse | null;
  todayKey: ReturnType<typeof getTodayDayKey>;
  now: Date;
  currentBlock: ScheduleBlockResponse | undefined;
}) => {
  const hours = Array.from(
    { length: (BOARD_END_MINUTES - BOARD_START_MINUTES) / 60 + 1 },
    (_, index) => BOARD_START_MINUTES / 60 + index
  );
  const currentTimeOffset =
    (now.getHours() * 60 + now.getMinutes() - BOARD_START_MINUTES) * PIXELS_PER_MINUTE;

  return (
    <section className="surface-card overflow-hidden p-0">
      <div className="flex flex-col gap-4 border-b border-[var(--border)] px-6 py-5 sm:px-8">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-end lg:justify-between">
          <div>
            <p className="metric-label">주간 시간표 영역</p>
            <h2 className="mt-2 text-2xl font-bold tracking-tight text-[var(--foreground)]">
              5분 슬롯 워크보드
            </h2>
            <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">
              요일별 블록을 한 화면에서 보면서 오늘 컬럼과 현재 블록을 강하게 강조합니다.
            </p>
          </div>

          <Link href="/schedule" className="btn-ghost border border-[var(--border)]">
            세부 시간표 열기
            <ArrowRight className="h-4 w-4" />
          </Link>
        </div>
      </div>

      <div className="overflow-x-auto">
        <div className="min-w-[980px] px-4 pb-4 pt-4 sm:px-6 sm:pb-6">
          <div className="grid grid-cols-[78px_repeat(7,minmax(120px,1fr))]">
            <div className="border-b border-r border-[var(--border)] bg-[var(--surface-muted)] px-3 py-3 text-xs font-semibold uppercase tracking-[0.18em] text-[var(--foreground-soft)]">
              Time
            </div>
            {DAY_ORDER.map((dayKey) => {
              const isToday = dayKey === todayKey;

              return (
                <div
                  key={dayKey}
                  className={`border-b border-r border-[var(--border)] px-3 py-3 last:border-r-0 ${
                    isToday ? "bg-[rgba(245,92,18,0.08)]" : "bg-[var(--surface-muted)]"
                  }`}
                >
                  <p className="text-center text-sm font-bold text-[var(--foreground)]">
                    {DAY_LABELS[dayKey]}
                  </p>
                  <p className="mt-1 text-center text-xs text-[var(--foreground-muted)]">
                    {DAY_FULL_LABELS[dayKey]}
                  </p>
                </div>
              );
            })}

            <div
              className="relative border-r border-[var(--border)] bg-[var(--surface-muted)]"
              style={{ height: `${BOARD_HEIGHT}px` }}
            >
              {hours.map((hour, index) => (
                <div
                  key={hour}
                  className={`absolute inset-x-0 px-3 text-xs text-[var(--foreground-soft)] ${
                    index === hours.length - 1 ? "" : ""
                  }`}
                  style={{ top: `${index * 60 * PIXELS_PER_MINUTE - 7}px` }}
                >
                  {String(hour).padStart(2, "0")}:00
                </div>
              ))}
            </div>

            {DAY_ORDER.map((dayKey) => {
              const day = sortBlocks(findDaySchedule(schedule, dayKey).blocks);
              const isToday = dayKey === todayKey;

              return (
                <div
                  key={`column-${dayKey}`}
                  className={`relative border-r border-[var(--border)] last:border-r-0 ${
                    isToday ? "bg-[rgba(245,92,18,0.05)]" : "bg-[var(--surface-50)]"
                  }`}
                  style={{ height: `${BOARD_HEIGHT}px` }}
                >
                  {hours.map((hour) => (
                    <div
                      key={`${dayKey}-${hour}`}
                      className="absolute inset-x-0 border-t border-dashed border-[var(--border)]"
                      style={{
                        top: `${(hour * 60 - BOARD_START_MINUTES) * PIXELS_PER_MINUTE}px`,
                      }}
                    />
                  ))}

                  {isToday &&
                  currentTimeOffset >= 0 &&
                  currentTimeOffset <= BOARD_HEIGHT ? (
                    <div
                      className="absolute inset-x-3 z-20 border-t-2 border-[var(--accent)]"
                      style={{ top: `${currentTimeOffset}px` }}
                    />
                  ) : null}

                  {day.length === 0 ? (
                    <div className="absolute inset-x-3 top-10 rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface-muted)] px-3 py-4 text-center text-xs text-[var(--foreground-soft)]">
                      빈 일정
                    </div>
                  ) : null}

                  {day.map((block) => {
                    const placement = getVisibleBlockPlacement(block);

                    if (!placement) {
                      return null;
                    }

                    const isCurrent = isToday && currentBlock?.id === block.id;
                    const tone = CATEGORY_TONES[block.category];

                    return (
                      <article
                        key={block.id}
                        className={`absolute left-2 right-2 overflow-hidden rounded-2xl border p-3 shadow-[0_12px_24px_rgba(39,36,29,0.08)] transition-transform ${
                          isCurrent ? "z-30 scale-[1.01]" : "z-10"
                        }`}
                        style={{
                          top: `${placement.top}px`,
                          height: `${placement.height}px`,
                          borderColor: isCurrent ? tone.line : `${tone.line}35`,
                          background: tone.badgeBackground,
                          color: tone.badgeText,
                        }}
                      >
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0">
                            <p className="text-[11px] font-bold uppercase tracking-[0.16em] opacity-75">
                              {CATEGORY_LABELS[block.category]}
                            </p>
                            <h3 className="mt-1 line-clamp-2 text-sm font-bold leading-5">
                              {block.activity}
                            </h3>
                          </div>
                          {isCurrent ? (
                            <span className="rounded-full bg-white/70 px-2 py-1 text-[10px] font-bold uppercase tracking-[0.12em]">
                              Now
                            </span>
                          ) : null}
                        </div>
                        <p className="mt-2 text-xs font-semibold opacity-85">
                          {formatTimeRange(block.startTime, block.endTime)}
                        </p>
                        {placement.height >= 80 && block.note ? (
                          <p className="mt-2 line-clamp-3 text-xs leading-5 opacity-80">
                            {block.note}
                          </p>
                        ) : null}
                      </article>
                    );
                  })}
                </div>
              );
            })}
          </div>
        </div>
      </div>
    </section>
  );
};

export const DashboardCanvas = ({ session }: DashboardCanvasProps) => {
  const schedule = useWeekSchedule();
  const goals = useGoals();
  const tasks = useTasksResource();
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 30000);
    return () => window.clearInterval(timer);
  }, []);

  const todayKey = getTodayDayKey(now);
  const todayBlocks = useMemo(() => {
    if (!schedule.data) {
      return [];
    }

    return sortBlocks(findDaySchedule(schedule.data, todayKey).blocks);
  }, [schedule.data, todayKey]);

  const currentBlock = getCurrentBlock(todayBlocks, now);
  const nextBlock = getNextBlock(todayBlocks, now);
  const activeGoals = useMemo(
    () => goals.data.filter((goal) => goal.status !== "COMPLETED"),
    [goals.data]
  );
  const allBlocks =
    schedule.data?.week.flatMap((day) => day.blocks) ?? [];
  const connectedLabel =
    session.googleConnectionStatus === "CONNECTED"
      ? "Google 연결됨"
      : "Google 연결 필요";
  const suggestions = useMemo(
    () =>
      buildSuggestions({
        session,
        now,
        currentBlock,
        nextBlock,
        goals: goals.data,
        pendingTasks: tasks.summary.pending,
        dueSoonCount: tasks.summary.dueSoon.length,
        unassignedCount: tasks.summary.unassigned.length,
      }),
    [
      currentBlock,
      goals.data,
      nextBlock,
      now,
      session,
      tasks.summary.dueSoon.length,
      tasks.summary.pending,
      tasks.summary.unassigned.length,
    ]
  );

  const refreshWorkspace = async () => {
    await Promise.all([schedule.refresh(), goals.refresh(), tasks.refresh()]);
  };

  const importedCount = allBlocks.filter(
    (block) => block.sourceType !== "MANUAL"
  ).length;
  const feedbackMessages = [schedule.error, goals.error, tasks.error].filter(Boolean);

  return (
    <div className="space-y-6">
      <PageIntro
        eyebrow="Integrated Workspace"
        title="오늘의 집중"
        description="주간 시간표, 할 일, 목표, 동기화 상태, 실행 제안을 한 화면에서 운영하는 MVP 워크스페이스입니다."
        meta={
          <>
            <span className="status-badge">{DAY_FULL_LABELS[todayKey]}</span>
            <span className="status-badge">{connectedLabel}</span>
            <span className="status-badge">
              마지막 동기화 {formatAbsoluteLabel(session.lastSyncAt)}
            </span>
          </>
        }
        actions={
          <>
            <button
              type="button"
              onClick={() => void refreshWorkspace()}
              className="btn-secondary"
            >
              <RefreshCw
                className={`h-4 w-4 ${
                  schedule.isLoading || goals.isLoading || tasks.isLoading
                    ? "animate-spin"
                    : ""
                }`}
              />
              새로고침
            </button>
            <Link href="/schedule" className="btn-secondary">
              <CalendarRange className="h-4 w-4" />
              시간표 보기
            </Link>
            <Link href="/goals" className="btn-secondary">
              <Target className="h-4 w-4" />
              목표 보기
            </Link>
            <Link href="/focus" className="btn-primary">
              <Crosshair className="h-4 w-4" />
              집중 화면
            </Link>
          </>
        }
      />

      {feedbackMessages.length > 0 ? (
        <div className="surface-card-muted p-4">
          <p className="text-sm font-semibold text-[var(--foreground)]">
            {feedbackMessages.join(" / ")}
          </p>
        </div>
      ) : null}

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <WorkspaceStat
          label="현재 상태"
          value={currentBlock ? "실행 중" : "전환 여유"}
          description={getTodaySummary(todayBlocks, now, currentBlock)}
          icon={<Clock3 className="h-5 w-5" />}
          tone={currentBlock ? "accent" : "success"}
        />
        <WorkspaceStat
          label="오늘 블록"
          value={formatCountLabel(todayBlocks.length, "개")}
          description="오늘 시간표에 등록된 일정 수"
          icon={<CalendarRange className="h-5 w-5" />}
        />
        <WorkspaceStat
          label="열린 할 일"
          value={formatCountLabel(tasks.summary.pending.length, "개")}
          description={`미배치 ${tasks.summary.unassigned.length}개 · 마감 임박 ${tasks.summary.dueSoon.length}개`}
          icon={<ListTodo className="h-5 w-5" />}
        />
        <WorkspaceStat
          label="활성 목표"
          value={formatCountLabel(activeGoals.length, "개")}
          description={`가져온 일정 ${importedCount}개 · 진행 중 목표 ${goals.data.filter((goal) => goal.status === "IN_PROGRESS").length}개`}
          icon={<Link2 className="h-5 w-5" />}
        />
      </div>

      <motion.div
        initial={{ opacity: 0, y: 12 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.35 }}
        className="grid gap-6 xl:grid-cols-[minmax(0,1.25fr)_380px]"
      >
        <CurrentFocusPanel
          now={now}
          currentBlock={currentBlock}
          nextBlock={nextBlock}
          activeGoals={activeGoals}
        />
        <SyncStatusWidget
          session={session}
          schedule={schedule.data}
          isRefreshing={schedule.isLoading || goals.isLoading || tasks.isLoading}
          onRefresh={() => void refreshWorkspace()}
        />
      </motion.div>

      <motion.div
        initial={{ opacity: 0, y: 16 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.4, delay: 0.05 }}
        className="grid gap-6 xl:grid-cols-[minmax(0,1.4fr)_380px]"
      >
        <WeeklyScheduleBoard
          schedule={schedule.data}
          todayKey={todayKey}
          now={now}
          currentBlock={currentBlock}
        />
        <div className="space-y-6">
          <TasksWidget resource={tasks} />
          <GoalsOverviewWidget goals={goals.data} />
        </div>
      </motion.div>

      <motion.div
        initial={{ opacity: 0, y: 18 }}
        animate={{ opacity: 1, y: 0 }}
        transition={{ duration: 0.45, delay: 0.1 }}
      >
        <AiSuggestionsWidget
          title="AI 조정 제안"
          description="실행 전환과 재배치가 필요한 지점을 우선순위 순서로 제안합니다."
          suggestions={suggestions.map((suggestion) => ({
            ...suggestion,
            title: suggestion.title.includes("AI")
              ? suggestion.title
              : `${suggestion.title}`,
          }))}
        />
      </motion.div>
    </div>
  );
};
