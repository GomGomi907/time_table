"use client";

import Link from "next/link";
import { useEffect, useMemo, useState } from "react";

import { AppShell } from "@/components/app-shell";
import { SectionHeader } from "@/components/section-header";
import { api } from "@/lib/api";
import {
  formatAiActionLabel,
  formatAiPreviewDetail,
  formatClockValue,
  formatServiceCopy,
} from "@/lib/format";
import {
  dismissOnboardingDayHandoffHint,
  OnboardingDayHandoff,
  readOnboardingDayHandoff,
} from "@/lib/onboarding-day-handoff";
import {
  CATEGORY_LABELS,
  DAY_FULL_LABELS,
  getCurrentDayName,
  getCurrentMinutes,
  getDailyBlocks,
  isBlockActive,
  minutesFromClock,
} from "@/lib/schedule";
import {
  DashboardMetrics,
  FocusCurrentView,
  Goal,
  RescheduleSuggestion,
  ScheduleBlock,
  SyncStatusEnvelope,
  WeekScheduleResponse,
} from "@/lib/types";
import { useSessionBootstrap } from "@/hooks/use-session-bootstrap";
import { useAppStore } from "@/stores/app-store";

interface DashboardData {
  week: WeekScheduleResponse | null;
  goals: Goal[];
  focus: FocusCurrentView | null;
  sync: SyncStatusEnvelope | null;
  suggestions: RescheduleSuggestion[];
  metrics: DashboardMetrics;
}

const EMPTY_STATE: DashboardData = {
  week: null,
  goals: [],
  focus: null,
  sync: null,
  suggestions: [],
  metrics: {
    averageGoalProgress: 0,
    weeklyShapeScore: 0,
    scheduleBlockCount: 0,
    growthBlockCount: 0,
    topGoal: null,
  },
};

type DayPeriodKey = "morning" | "afternoon" | "evening";

const DAY_PERIODS: Array<{ key: DayPeriodKey; label: string; range: string }> = [
  { key: "morning", label: "오전", range: "12시 전" },
  { key: "afternoon", label: "오후", range: "12–18시" },
  { key: "evening", label: "저녁", range: "18시 이후" },
];

function buildTrustLog(sync: SyncStatusEnvelope | null, pendingSuggestion: RescheduleSuggestion | null) {
  const meta = sync?.meta ?? {};
  const items = [
    pendingSuggestion
      ? "조정안은 사용자가 적용하기 전까지 앱 일정이나 Google 캘린더와 할 일을 바꾸지 않습니다."
      : "확인 대기 중인 조정안은 없습니다.",
    meta.externalWriteEnabled
      ? "승인하면 앱 일정에 저장한 뒤 Google 캘린더와 할 일에도 반영합니다."
      : "현재는 Google 읽기 모드입니다. 승인하면 앱 일정에 먼저 저장하고 Google 반영은 쓰기 재연결 후 처리합니다.",
    (meta.pendingProviderWriteCount ?? 0) > 0
      ? `승인된 변경 ${(meta.pendingProviderWriteCount ?? 0)}건이 Google 반영을 기다립니다.`
      : "Google 반영 대기열은 비어 있거나 안정 상태입니다.",
  ];

  return items;
}

function buildSuggestionEvidence(item: RescheduleSuggestion["previewItems"][number]) {
  const reason = item.reason?.trim();
  if (reason) {
    return reason;
  }

  if (item.actionType === "SHIFT_BLOCK") {
    return "겹치는 일정과 보호 시간을 피해 시작·종료 시간을 다시 잡습니다.";
  }
  if (item.actionType === "CREATE_BLOCK") {
    return "비어 있는 시간대에 필요한 실행 블록을 새로 확보합니다.";
  }
  if (item.actionType === "UPDATE_BLOCK") {
    return "기존 블록의 길이·메모·성격을 현재 요청에 맞게 조정합니다.";
  }
  if (item.actionType === "DELETE_BLOCK") {
    return "더 이상 필요하지 않거나 충돌을 만드는 블록을 제거 후보로 둡니다.";
  }

  return "사용자 요청과 이번 주 일정을 비교해 만든 설명입니다.";
}

function isUserFacingPreviewItem(item: RescheduleSuggestion["previewItems"][number]) {
  const rawText = [item.title, item.detail, item.reason].filter(Boolean).join(" ");
  return !/APP_AI_ENABLED|Gemini|AI disabled|endpoint/i.test(rawText);
}

function buildPreviewDigest(items: RescheduleSuggestion["previewItems"], limit = 3) {
  const groups = new Map<
    string,
    RescheduleSuggestion["previewItems"][number] & { groupedCount: number }
  >();

  for (const item of items.filter(isUserFacingPreviewItem)) {
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

function sortScheduleBlocks(blocks: ScheduleBlock[]) {
  return [...blocks].sort((left, right) => minutesFromClock(left.startTime) - minutesFromClock(right.startTime));
}

function dedupeTodayBlocks(blocks: ScheduleBlock[]) {
  const seen = new Set<string>();
  return blocks.filter((block) => {
    const key = [
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

function blockOverlapsPeriod(block: ScheduleBlock, period: DayPeriodKey) {
  const { start, end } = scheduleInterval(block);
  const periodRange: Record<DayPeriodKey, [number, number]> = {
    morning: [0, 12 * 60],
    afternoon: [12 * 60, 18 * 60],
    evening: [18 * 60, 24 * 60],
  };
  const [periodStart, periodEnd] = periodRange[period];

  return start < periodEnd && end > periodStart;
}

function scheduleInterval(block: ScheduleBlock) {
  const start = minutesFromClock(block.startTime);
  const endClock = minutesFromClock(block.endTime);
  const duration = endClock > start ? endClock - start : endClock + 24 * 60 - start;

  return {
    start,
    end: start + Math.max(duration, 1),
  };
}

function countOverlappingPairs(blocks: ScheduleBlock[]) {
  let count = 0;
  const intervals = blocks.map(scheduleInterval);

  for (let left = 0; left < intervals.length; left += 1) {
    for (let right = left + 1; right < intervals.length; right += 1) {
      if (intervals[left].start < intervals[right].end && intervals[right].start < intervals[left].end) {
        count += 1;
      }
    }
  }

  return count;
}

function countTightTransitions(blocks: ScheduleBlock[]) {
  const intervals = sortScheduleBlocks(blocks).map(scheduleInterval);
  let count = 0;

  for (let index = 0; index < intervals.length - 1; index += 1) {
    const gap = intervals[index + 1].start - intervals[index].end;
    if (gap >= 0 && gap <= 15) {
      count += 1;
    }
  }

  return count;
}

function pickBriefingScheduleRows(blocks: ScheduleBlock[], _currentMinutes: number, limit = 4) {
  if (blocks.length <= limit) {
    return blocks;
  }

  return blocks.slice(0, limit);
}

function buildDayPeriodSummary(blocks: ScheduleBlock[]) {
  const grouped: Record<DayPeriodKey, ScheduleBlock[]> = {
    morning: [],
    afternoon: [],
    evening: [],
  };

  blocks.forEach((block) => {
    DAY_PERIODS.forEach((period) => {
      if (blockOverlapsPeriod(block, period.key)) {
        grouped[period.key].push(block);
      }
    });
  });

  return DAY_PERIODS.map((period) => {
    const periodBlocks = grouped[period.key];
    return {
      ...period,
      count: periodBlocks.length,
      lead: periodBlocks[0]?.activity ?? null,
    };
  });
}

function buildDayHeadline(blocks: ScheduleBlock[]) {
  if (!blocks.length) {
    return "오늘 예정된 일정이 없습니다.";
  }

  return `오늘 일정은 ${blocks.length}개입니다.`;
}

function buildBriefingLine({
  blocks,
  currentMinutes,
  liveOrNextBlock,
}: {
  blocks: ScheduleBlock[];
  currentMinutes: number;
  liveOrNextBlock: ScheduleBlock | null;
}) {
  if (!blocks.length) {
    return "필요하면 주간 일정에서 시간을 배치하세요.";
  }

  const overlapCount = countOverlappingPairs(blocks);
  const tightTransitionCount = countTightTransitions(blocks);
  const finishedCount = blocks.filter((block) => scheduleInterval(block).end <= currentMinutes).length;
  const nextPart = liveOrNextBlock
    ? `다음은 ${formatClockValue(liveOrNextBlock.startTime)} · ${formatServiceCopy(liveOrNextBlock.activity)}`
    : "남은 일정 없음";
  const overlapPart = overlapCount
    ? `겹침 ${overlapCount}건 확인 필요`
    : tightTransitionCount
      ? `빡빡한 전환 ${tightTransitionCount}건 확인 필요`
      : "겹침 없음";
  const finishedPart = finishedCount ? `지난 일정 ${finishedCount}개` : "지난 일정 없음";

  return `${nextPart} · ${overlapPart} · ${finishedPart}`;
}

function buildPrimaryAction({
  pendingSuggestion,
  currentItem,
  recommendedTask,
}: {
  pendingSuggestion: RescheduleSuggestion | null;
  currentItem: FocusCurrentView["currentItem"];
  recommendedTask: FocusCurrentView["recommendedTasks"][number] | null;
}) {
  if (pendingSuggestion) {
    return {
      label: "조정안 검토",
      href: "/schedule",
      detail: "제안한 변경은 승인 전까지 적용되지 않습니다.",
    };
  }

  if (currentItem || recommendedTask) {
    return {
      label: "실행 모드 시작",
      href: "/focus",
      detail: currentItem
        ? `${formatServiceCopy(currentItem.title)} 진행 상태를 확인해보세요.`
        : `${formatServiceCopy(recommendedTask?.title ?? "추천 할 일")}부터 시작할 수 있습니다.`,
    };
  }

  return {
    label: "주간 일정 보기",
    href: "/schedule",
    detail: "오늘 실행할 항목이 없으면 주간 일정에서 시간을 정리하세요.",
  };
}

export function DashboardView() {
  const { session, refreshSession } = useSessionBootstrap();
  const showNotice = useAppStore((state) => state.showNotice);
  const [data, setData] = useState<DashboardData>(EMPTY_STATE);
  const [onboardingHandoff, setOnboardingHandoff] = useState<OnboardingDayHandoff | null>(null);
  const [status, setStatus] = useState<"loading" | "ready" | "error">("loading");
  const [error, setError] = useState<string | null>(null);
  const [isMutating, setIsMutating] = useState(false);

  async function loadDashboard() {
    if (!session?.authenticated) {
      return;
    }

    try {
      setStatus("loading");
      setError(null);
      const summary = await api.getDashboardSummary();
      setData(summary.data);
      setStatus("ready");
    } catch (loadError) {
      setStatus("error");
      setError(loadError instanceof Error ? loadError.message : "데이터를 불러오지 못했습니다.");
    }
  }

  useEffect(() => {
    if (!session?.authenticated) {
      return;
    }

    void loadDashboard();
  }, [session?.authenticated]);

  useEffect(() => {
    if (!session?.userId) {
      setOnboardingHandoff(null);
      return;
    }

    setOnboardingHandoff(readOnboardingDayHandoff(session.userId));
  }, [session?.userId]);

  async function withDashboardMutation(
    action: () => Promise<unknown>,
    successTitle: string,
    errorTitle: string,
  ) {
    if (isMutating) {
      return;
    }

    try {
      setIsMutating(true);
      await action();
      showNotice({
        tone: "success",
        title: successTitle,
      });
      await Promise.all([loadDashboard(), refreshSession({ silent: true })]);
    } catch (mutationError) {
      showNotice({
        tone: "error",
        title: errorTitle,
        detail:
          mutationError instanceof Error
            ? mutationError.message
            : "잠시 후 다시 시도해 주세요.",
      });
    } finally {
      setIsMutating(false);
    }
  }

  const pendingSuggestion =
    data.suggestions.find((suggestion) => suggestion.status === "pending") ?? null;
  const todayName = getCurrentDayName(session?.timezone);
  const todayLabel = DAY_FULL_LABELS[todayName] ?? "오늘";
  const currentMinutes = getCurrentMinutes(session?.timezone);
  const currentItem = data.focus?.currentItem ?? null;
  const recommendedTask = data.focus?.recommendedTasks[0] ?? null;
  const previewItems = buildPreviewDigest(pendingSuggestion?.previewItems ?? [], 2);
  const trustLog = buildTrustLog(data.sync, pendingSuggestion);
  const fullTodaySchedule = useMemo(() => {
    const blocks = getDailyBlocks(data.week, todayName);
    const nonSleepBlocks = blocks.filter((block) => block.category !== "SLEEP");
    return sortScheduleBlocks(dedupeTodayBlocks(nonSleepBlocks.length ? nonSleepBlocks : blocks));
  }, [data.week, todayName]);
  const periodSummary = useMemo(() => buildDayPeriodSummary(fullTodaySchedule), [fullTodaySchedule]);
  const dayHeadline = useMemo(() => buildDayHeadline(fullTodaySchedule), [fullTodaySchedule]);
  const primaryAction = buildPrimaryAction({ pendingSuggestion, currentItem, recommendedTask });
  const liveOrNextBlock =
    fullTodaySchedule.find((block) => isBlockActive(block, currentMinutes)) ??
    fullTodaySchedule.find((block) => minutesFromClock(block.startTime) > currentMinutes) ??
    fullTodaySchedule[0] ??
    null;
  const nextShiftDescription = liveOrNextBlock
    ? buildBriefingLine({ blocks: fullTodaySchedule, currentMinutes, liveOrNextBlock })
    : buildBriefingLine({ blocks: fullTodaySchedule, currentMinutes, liveOrNextBlock: null });
  const briefingScheduleRows = useMemo(
    () => pickBriefingScheduleRows(fullTodaySchedule, currentMinutes),
    [fullTodaySchedule, currentMinutes],
  );
  const hiddenScheduleCount = Math.max(fullTodaySchedule.length - briefingScheduleRows.length, 0);
  const overlapCount = countOverlappingPairs(fullTodaySchedule);
  const tightTransitionCount = countTightTransitions(fullTodaySchedule);
  const scheduleRiskSignal = overlapCount
    ? {
        tone: "danger",
        label: "겹침",
        detail: `${overlapCount}건 확인 필요`,
      }
    : tightTransitionCount
      ? {
          tone: "warning",
          label: "빡빡한 전환",
          detail: `${tightTransitionCount}건 확인 필요`,
        }
      : {
          tone: "safe",
          label: "일정 간격",
          detail: "여유 있음",
        };
  const pendingProviderWriteCount = data.sync?.meta.pendingProviderWriteCount ?? 0;

  function handleDismissOnboardingHint() {
    dismissOnboardingDayHandoffHint(session?.userId, "dashboard");
    setOnboardingHandoff(readOnboardingDayHandoff(session?.userId));
  }

  return (
    <AppShell
      eyebrow="오늘 확인"
      title="오늘 브리핑"
      description="오늘 일정과 다음 행동을 먼저 확인해보세요. 조정안은 승인하면 반영합니다."
      screenQuestion="오늘 예정된 일정은 무엇인가요?"
      primaryActionLabel={primaryAction.label}
      actions={
        <button className="ghost-btn" onClick={() => void loadDashboard()}>
          새로고침
        </button>
      }
    >
      {!data.week && !data.focus && data.goals.length === 0 && status === "loading" ? (
        <section className="surface-card empty-state">
          <strong>오늘 브리핑을 준비하는 중입니다.</strong>
          <p>오늘 예정된 일정부터 확인하고 있습니다.</p>
        </section>
      ) : null}

      {!data.week && !data.focus && data.goals.length === 0 && status === "error" ? (
        <section className="surface-card empty-state">
          <strong>오늘 브리핑을 불러오지 못했습니다.</strong>
          <p>{error ?? "서비스 응답을 다시 확인해 주세요."}</p>
          <button className="solid-btn" onClick={() => void loadDashboard()}>
            다시 불러오기
          </button>
        </section>
      ) : null}

      {data.week || data.focus || data.goals.length ? (
        <>
          <section
            className={`today-briefing-shell ${pendingSuggestion ? "has-pending-approval" : ""}`}
            aria-label="오늘 브리핑 핵심"
          >
            <article className="surface-card today-flow-card">
              <div className="today-flow-hero">
                <div className="today-flow-copy">
                  <p className="eyebrow">{todayLabel} 예정</p>
                  <h2>{dayHeadline}</h2>
                  <p>{nextShiftDescription}</p>
                </div>
                <Link className="solid-btn link-btn today-primary-action" href={primaryAction.href}>
                  {primaryAction.label}
                </Link>
              </div>

              <div className="today-risk-strip" aria-label="오늘 일정 확인 신호">
                <span className={`today-risk-chip ${scheduleRiskSignal.tone}`}>
                  <b>{scheduleRiskSignal.label}</b>
                  {scheduleRiskSignal.detail}
                </span>
                <span className="today-risk-chip safe">
                  <b>승인 전 안전</b>
                  조정안은 적용 전까지 대기
                </span>
                <span className="today-risk-chip action">
                  <b>다음 행동</b>
                  {primaryAction.label}
                </span>
              </div>

              <div className="day-period-strip" aria-label="오늘 시간대 요약">
                {periodSummary.map((period) => (
                  <div
                    className={period.count > 0 ? "day-period-chip filled" : "day-period-chip"}
                    key={period.key}
                  >
                    <span>{period.label}</span>
                    <strong>{period.count ? `${period.count}개` : "비어 있음"}</strong>
                    <p>{period.lead ? formatServiceCopy(period.lead) : period.range}</p>
                  </div>
                ))}
              </div>
            </article>

            <article className="surface-card today-schedule-card today-schedule-panel">
              <SectionHeader
                eyebrow="바로 볼 것"
                title="오늘 일정 핵심"
                trailing={<span className="accent-pill">전체 {fullTodaySchedule.length}개</span>}
              />
              <div className="today-schedule-list">
                {briefingScheduleRows.length ? (
                  briefingScheduleRows.map((block) => {
                    const isLive = isBlockActive(block, currentMinutes);
                    return (
                      <div className={`today-schedule-row ${isLive ? "live" : ""}`} key={block.id}>
                        <span className="time-chip">
                          {formatClockValue(block.startTime)}–{formatClockValue(block.endTime)}
                        </span>
                        <div>
                          <strong>{formatServiceCopy(block.activity)}</strong>
                          <p>
                            {CATEGORY_LABELS[block.category] ?? block.category}
                            {isLive ? " · 지금 진행 중" : ""}
                          </p>
                        </div>
                      </div>
                    );
                  })
                ) : (
                  <div className="empty-state subtle">
                    <strong>오늘 일정이 아직 없습니다.</strong>
                    <p>주간 일정에서 시간 블록을 추가하면 오늘 브리핑에 바로 표시됩니다.</p>
                  </div>
                )}
              </div>
              {hiddenScheduleCount ? (
                <div className="today-schedule-more">
                  <span>나머지 {hiddenScheduleCount}개 일정은 접어 두었습니다.</span>
                  <Link className="ghost-btn link-btn" href="/schedule">
                    전체 보기
                  </Link>
                </div>
              ) : null}
            </article>

            <aside className={`surface-card ai-approval-card ${pendingSuggestion ? "pending" : "quiet"}`}>
              <p className="eyebrow">{pendingSuggestion ? "승인 필요" : "조정 상태"}</p>
              <h2>{pendingSuggestion ? "적용 전 조정안을 확인해보세요." : "지금 승인할 조정안은 없습니다."}</h2>
              <p className="section-header-note">
                {pendingSuggestion
                  ? formatServiceCopy(pendingSuggestion.summary)
                  : "필요할 때만 조정안을 만들고, 사용자가 승인하기 전에는 일정을 바꾸지 않습니다."}
              </p>

              {pendingSuggestion ? (
                <>
                  <div className="ai-diff-preview compact">
                    {previewItems.length ? (
                      previewItems.map((item) => (
                        <div className="ai-diff-row" key={`${item.actionType}-${item.title}-${item.detail}`}>
                          <span>{formatAiActionLabel(item.actionType)}</span>
                          <strong>{formatServiceCopy(item.title)}</strong>
                          <div className="ai-diff-line">
                            <b>확인한 내용</b>
                            <p>{buildSuggestionEvidence(item)}</p>
                          </div>
                          <div className="ai-diff-line after">
                            <b>예상 영향</b>
                            <p>
                              {formatAiPreviewDetail(item.detail) ??
                                "빈 시간과 보호 시간을 기준으로 재배치합니다."}
                            </p>
                          </div>
                        </div>
                      ))
                    ) : (
                      <div className="ai-diff-row">
                        <span>검토</span>
                        <strong>{formatServiceCopy(pendingSuggestion.summary)}</strong>
                        <div className="ai-diff-line">
                          <b>적용 전</b>
                          <p>현재 일정은 그대로 유지됩니다.</p>
                        </div>
                        <div className="ai-diff-line after">
                          <b>적용 후</b>
                          <p>검토한 조정안만 일정표에 반영됩니다.</p>
                        </div>
                      </div>
                    )}
                  </div>
                  <p className="approval-guard-copy">
                    승인 전에는 앱 일정이나 Google 캘린더와 할 일을 바꾸지 않습니다.
                  </p>
                  {pendingProviderWriteCount > 0 ? (
                    <p className="approval-guard-copy sync-pending-copy">
                      Google 반영 대기 {pendingProviderWriteCount}건이 있습니다. 앱 일정에는 저장되어 있습니다.
                    </p>
                  ) : null}
                  <div className="suggestion-actions approval-actions">
                    <button
                      className="ghost-btn"
                      disabled={isMutating}
                      onClick={() =>
                        void withDashboardMutation(
                          () => api.rejectSuggestion(pendingSuggestion.id),
                          "조정안을 보류했습니다.",
                          "조정안 처리에 실패했습니다.",
                        )
                      }
                      type="button"
                    >
                      보류
                    </button>
                    <button
                      className="solid-btn"
                      disabled={isMutating || !pendingSuggestion.executable}
                      onClick={() =>
                        void withDashboardMutation(
                          () => api.applySuggestion(pendingSuggestion.id),
                          "조정안을 적용했습니다.",
                          "조정안 처리에 실패했습니다.",
                        )
                      }
                      type="button"
                    >
                      승인 적용
                    </button>
                  </div>
                </>
              ) : (
                <div className="quiet-assistant-list">
                  {trustLog.slice(0, 2).map((item) => (
                    <span key={item}>{item}</span>
                  ))}
                </div>
              )}

              <div className="next-action-card">
                <span>다음 행동</span>
                <strong>{primaryAction.label}</strong>
                <p>{primaryAction.detail}</p>
                <Link className="ghost-btn link-btn" href={primaryAction.href}>
                  이동하기
                </Link>
              </div>
            </aside>
          </section>

          {onboardingHandoff && !onboardingHandoff.dashboardDismissed ? (
            <section className="surface-card onboarding-day-hint">
              <div className="onboarding-day-hint-head">
                <div className="onboarding-day-hint-copy">
                  <p className="eyebrow">첫날 안내</p>
                  <h2>
                    {onboardingHandoff.appliedSuggestion
                      ? "첫 주 일정을 바로 반영했습니다."
                      : "첫 주 일정 기준을 먼저 잡아 두었습니다."}
                  </h2>
                  <p>
                    {onboardingHandoff.suggestionExplanation ??
                      "처음 설정에서 정한 생활 리듬을 기준으로 이번 주 일정 조정이 시작됩니다."}
                  </p>
                </div>

                <button
                  className="ghost-btn secondary-action-btn"
                  onClick={handleDismissOnboardingHint}
                  type="button"
                >
                  닫기
                </button>
              </div>

              <div className="onboarding-day-answer-list">
                {onboardingHandoff.answers.slice(0, 4).map((answer) => (
                  <div key={answer.id} className="onboarding-day-chip">
                    <strong>{answer.title}</strong>
                    <span>{answer.value}</span>
                  </div>
                ))}
              </div>

              <div className="onboarding-day-hint-actions">
                <p className="micro-copy">
                  {onboardingHandoff.suggestionSummary ??
                    "주간 일정에서 첫 조정안을 확인하거나, 다시 다듬어 달라고 요청할 수 있습니다."}
                </p>
                <Link className="solid-btn link-btn" href="/schedule">
                  주간 일정 보기
                </Link>
              </div>
            </section>
          ) : null}

        </>
      ) : null}
    </AppShell>
  );
}
