"use client";

import { ChangeEvent, FormEvent, useEffect, useRef, useState, useTransition } from "react";

import { AppShell } from "@/components/app-shell";
import { SectionHeader } from "@/components/section-header";
import { api } from "@/lib/api";
import { formatClockValue } from "@/lib/format";
import {
  CATEGORY_LABELS,
  DAY_FULL_LABELS,
  DAY_LABELS,
  DAY_ORDER,
  DAY_TRACK_HEIGHT,
  PIXELS_PER_MINUTE,
  getCurrentDayName,
  getCurrentMinutes,
  getDailyBlocks,
  getLaidOutBlocks,
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

function WeeklyGrid({
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

  return (
    <div className="week-grid-shell">
      <div className="week-grid-scroll">
        <div className="week-grid-header">
          <div className="week-grid-corner">시간</div>
          {DAY_ORDER.map((day) => {
            const blocks = getDailyBlocks(week, day);
            const isToday = currentDay === day;
            return (
              <div
                key={day}
                className={`week-grid-day-head ${isToday ? "today" : ""} ${blocks.length === 0 ? "empty" : ""}`}
              >
                <span>{DAY_LABELS[day]}</span>
              </div>
            );
          })}
        </div>

        <div className="week-grid-body">
          <div className="schedule-time-axis" style={{ height: `${DAY_TRACK_HEIGHT}px` }}>
            {Array.from({ length: 25 }, (_, index) => (
              <div
                key={index}
                className={`schedule-time-tick ${index === 0 ? "start" : ""} ${index === 24 ? "end" : ""}`}
                style={{ top: `${index * 60 * PIXELS_PER_MINUTE}px` }}
              >
                {String(index).padStart(2, "0")}:00
              </div>
            ))}
          </div>

          {DAY_ORDER.map((day) => {
            const blocks = getDailyBlocks(week, day);
            const laidOutBlocks = getLaidOutBlocks(blocks);
            return (
              <div
                key={day}
                className={`schedule-day-column ${currentDay === day ? "today" : ""}`}
                style={{ height: `${DAY_TRACK_HEIGHT}px` }}
              >
                {currentDay === day ? (
                  <div
                    className="schedule-now-line"
                    style={{ top: `${currentMinutes * PIXELS_PER_MINUTE}px` }}
                  >
                    <span>지금</span>
                  </div>
                ) : null}

                {laidOutBlocks.map((block) => (
                  <button
                    key={block.id}
                    type="button"
                    aria-label={`${DAY_FULL_LABELS[day]} ${formatClockValue(block.startTime)}부터 ${formatClockValue(block.endTime)}까지 ${block.activity} 수정`}
                    className={`schedule-block ${categoryTone(block.category)} ${block.isCompact ? "compact" : ""} ${block.isTight ? "tight" : ""}`}
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
                    <strong>{block.activity}</strong>
                    <span className="event-time">
                      {formatClockValue(block.startTime)} - {formatClockValue(block.endTime)}
                    </span>
                    {block.note ? <p className="event-note">{block.note}</p> : null}
                  </button>
                ))}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}

export function ScheduleView() {
  const { session } = useSessionBootstrap();
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
  const [isPending, startTransition] = useTransition();
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

    try {
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
      await loadSchedulePage();
    } catch (saveError) {
      showNotice({
        tone: "error",
        title: editingBlock ? "일정 블록 수정에 실패했습니다." : "일정 블록 추가에 실패했습니다.",
        detail: saveError instanceof Error ? saveError.message : "입력값을 다시 확인해 주세요.",
      });
    }
  }

  async function handleDeleteBlock() {
    if (!editingBlock) {
      return;
    }

    const confirmed = window.confirm(`"${editingBlock.activity}" 블록을 삭제할까요?`);
    if (!confirmed) {
      return;
    }

    try {
      await api.deleteScheduleBlock(editingBlock.id);
      setEditingBlock(null);
      setForm(DEFAULT_FORM);
      setCreateModalOpen(false);
      showNotice({
        tone: "success",
        title: "일정 블록을 삭제했습니다.",
      });
      await loadSchedulePage();
    } catch (deleteError) {
      showNotice({
        tone: "error",
        title: "일정 블록 삭제에 실패했습니다.",
        detail: deleteError instanceof Error ? deleteError.message : "잠시 후 다시 시도해 주세요.",
      });
    }
  }

  async function handleRequestSuggestion(event?: FormEvent<HTMLFormElement>) {
    event?.preventDefault();

    const trimmedReason = requestReason.trim();
    if (!trimmedReason) {
      showNotice({
        tone: "error",
        title: "AI 요청 내용을 먼저 적어 주세요.",
      });
      return;
    }

    try {
      await api.requestManualReschedule(trimmedReason);
      setRequestReason("");
      showNotice({
        tone: "success",
        title: "재조율 요청을 생성했습니다.",
      });
      await loadSchedulePage();
    } catch (requestError) {
      showNotice({
        tone: "error",
        title: "재조율 요청에 실패했습니다.",
        detail:
          requestError instanceof Error ? requestError.message : "잠시 후 다시 시도해 주세요.",
      });
    }
  }

  async function handleSuggestionDecision(action: "apply" | "reject", suggestionId: string) {
    try {
      if (action === "apply") {
        await api.applySuggestion(suggestionId);
      } else {
        await api.rejectSuggestion(suggestionId);
      }

      showNotice({
        tone: "success",
        title: action === "apply" ? "제안을 시간표에 반영했습니다." : "제안을 보류했습니다.",
      });
      await loadSchedulePage();
    } catch (decisionError) {
      showNotice({
        tone: "error",
        title: "제안 처리에 실패했습니다.",
        detail:
          decisionError instanceof Error
            ? decisionError.message
            : "잠시 후 다시 시도해 주세요.",
      });
    }
  }

  const pendingSuggestions = data.suggestions.filter((suggestion) => suggestion.status === "pending");

  return (
    <AppShell
      eyebrow="주간 플래너"
      title="주간 플래너"
      description="AI로 시간표를 조정하고, 필요한 경우에만 직접 블록을 추가합니다."
      actions={
        <button className="ghost-btn" onClick={() => void loadSchedulePage()}>
          새로고침
        </button>
      }
    >
      {!data.week && status === "loading" ? (
        <section className="surface-card empty-state">
          <strong>주간 플래너를 불러오는 중입니다.</strong>
          <p>시간표와 대기 중 제안을 함께 준비하고 있습니다.</p>
        </section>
      ) : null}

      {!data.week && status === "error" ? (
        <section className="surface-card empty-state">
          <strong>주간 플래너를 불러오지 못했습니다.</strong>
          <p>{error ?? "백엔드 연결을 다시 확인해 주세요."}</p>
        </section>
      ) : null}

      {data.week ? (
      <section className="planner-layout schedule-layout">
        <article className="planner-board schedule-main-board">
          <SectionHeader
            eyebrow="주간"
            title="주간 시간표"
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

          <section className="ai-compose-card">
            <SectionHeader
              eyebrow="AI 요청"
              title="시간표 조정 요청"
              description="자연어로 적으면 suggestion으로 만들고, 검토 후 시간표에 반영할 수 있습니다."
            />

            <form className="ai-compose-form" onSubmit={(event) => void handleRequestSuggestion(event)}>
              <textarea
                className="ai-compose-textarea"
                value={requestReason}
                onChange={(event) => setRequestReason(event.target.value)}
                placeholder="예: 화요일 저녁 운동 블록을 30분 뒤로 밀고, 금요일 오전엔 딥워크 2시간 확보해 줘"
                rows={3}
              />
              <div className="ai-compose-actions">
                <button
                  className="ghost-btn"
                  disabled={isPending}
                  type="button"
                  onClick={openCreateModal}
                >
                  일정 직접 추가
                </button>
                <button className="solid-btn" disabled={isPending} type="submit">
                  AI에게 요청
                </button>
              </div>
            </form>

            {pendingSuggestions.length ? (
              <div className="ai-suggestion-strip">
                {pendingSuggestions.slice(0, 2).map((suggestion) => (
                  <div className="ai-suggestion-card" key={suggestion.id}>
                    <strong>{suggestion.summary}</strong>
                    <p>{suggestion.explanation}</p>
                    <div className="suggestion-actions">
                      <button
                        className="ghost-btn"
                        type="button"
                        onClick={() =>
                          startTransition(() =>
                            void handleSuggestionDecision("reject", suggestion.id),
                          )
                        }
                      >
                        보류
                      </button>
                      <button
                        className="solid-btn"
                        type="button"
                        onClick={() =>
                          startTransition(() =>
                            void handleSuggestionDecision("apply", suggestion.id),
                          )
                        }
                      >
                        적용
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            ) : null}
          </section>

          <WeeklyGrid week={data.week} onBlockSelect={openEditModal} timeZone={session?.timezone} />
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
                    placeholder="예: 딥 워크 블록"
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
                    onClick={() => void handleDeleteBlock()}
                  >
                    삭제
                  </button>
                ) : null}
                <button
                  className="ghost-btn"
                  type="button"
                  onClick={() => setCreateModalOpen(false)}
                >
                  취소
                </button>
                <button className="solid-btn" type="submit">
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
