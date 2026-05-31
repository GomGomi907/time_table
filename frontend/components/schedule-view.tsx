"use client";

import { ChangeEvent, FormEvent, useEffect, useRef, useState } from "react";

import { AppShell } from "@/components/app-shell";
import { SectionHeader } from "@/components/section-header";
import { SuggestionReviewCard } from "@/components/suggestion-review-card";
import { api } from "@/lib/api";
import { formatClockValue, formatServiceCopy, getSuggestionDisplayState } from "@/lib/format";
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

function shouldShowInWeeklyStack(block: ScheduleBlock) {
  return block.category !== "SLEEP";
}

function getVisibleWeekBlocks(week: WeekScheduleResponse | null) {
  return dedupeBlocks(getWeekBlocks(week).filter(shouldShowInWeeklyStack));
}

function getVisibleDailyBlocks(week: WeekScheduleResponse | null, dayOfWeek: string) {
  return dedupeBlocks(getDailyBlocks(week, dayOfWeek).filter(shouldShowInWeeklyStack));
}

function formatDurationLabel(totalMinutes: number) {
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;

  if (hours === 0) {
    return `${minutes}분`;
  }

  return minutes === 0 ? `${hours}시간` : `${hours}시간 ${minutes}분`;
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

      <div className="week-stack-shell">
        <div className="week-planner-head">
          <div>
            <p className="panel-kicker">주간 일정</p>
            <h2>이번 주 일정 흐름</h2>
            <p>
              고정된 시간표 대신 아침부터 밤까지의 일정이 요일별로 쌓여 보입니다.
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
          <button
            className={`current-schedule-card ${categoryTone(focusScheduleBlock.category)}`}
            type="button"
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
            {focusScheduleBlock.note ? <span className="current-schedule-note">{formatServiceCopy(focusScheduleBlock.note)}</span> : null}
          </button>
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
                      return (
                        <button
                          key={block.id}
                          type="button"
                          aria-label={`${DAY_FULL_LABELS[day]} ${formatClockValue(block.startTime)}부터 ${formatClockValue(block.endTime)}까지 ${formatServiceCopy(block.activity)} 수정`}
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
                          {block.note ? <p className="event-note">{formatServiceCopy(block.note)}</p> : null}
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
        detail: saveError instanceof Error ? saveError.message : "입력값 확인이 필요합니다.",
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
        detail: deleteError instanceof Error ? deleteError.message : "잠시 후 다시 시도하면 됩니다.",
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
      if (action === "apply") {
        await api.applySuggestion(suggestionId);
      } else {
        await api.rejectSuggestion(suggestionId);
      }

      showNotice({
        tone: "success",
        title: action === "apply" ? "변경을 일정표에 반영했습니다." : "변경을 보류했습니다.",
      });
      await Promise.all([loadSchedulePage(), refreshSession({ silent: true })]);
    } catch (decisionError) {
      showNotice({
        tone: "error",
        title: "변경 처리에 실패했습니다.",
        detail:
          decisionError instanceof Error
            ? decisionError.message
            : "잠시 후 다시 시도하면 됩니다.",
      });
    } finally {
      setIsMutating(false);
    }
  }


  const pendingSuggestions = data.suggestions.filter((suggestion) => suggestion.status === "pending");
  const aiRequestRail = (
    <section className="ai-compose-card ai-chat-card" data-testid="schedule-ai-right-rail" aria-label="AI 변경 요청 대화">
      <div className="ai-chat-head">
        <div>
          <p className="panel-kicker">AI 변경 요청</p>
          <h2>요청과 답변</h2>
        </div>
        <span className="accent-pill" data-testid="schedule-pending-count">
          {pendingSuggestions.length ? `${pendingSuggestions.length}건 대기` : "대기 없음"}
        </span>
      </div>

      <div className="ai-chat-thread" aria-live="polite">
        {pendingSuggestions.length ? (
          pendingSuggestions.map((suggestion) => {
            const display = getSuggestionDisplayState(suggestion);
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
                    kicker="검토"
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
            onClick={openCreateModal}
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
      actions={
        <button className="ghost-btn" disabled={status === "loading"} onClick={() => void loadSchedulePage()}>
          {status === "loading" ? "새로고침 중..." : "새로고침"}
        </button>
      }
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
            <SectionHeader
              eyebrow="시간표"
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

            <div className="schedule-device-layout">
              <section className="schedule-calendar-panel" aria-label="반응형 주간 일정">
                <WeeklyStack
                  week={data.week}
                  onBlockSelect={openEditModal}
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
