"use client";

import { CalendarRange, Loader2, Pencil, Plus, RefreshCw, Sparkles, Trash2 } from "lucide-react";
import { useMemo, useState } from "react";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type { ScheduleBlockResponse, ScheduleBlockWriteRequest } from "@/shared/api/types";
import { useWeekSchedule } from "./useWeekSchedule";
import {
  CATEGORY_LABELS,
  DAY_FULL_LABELS,
  DAY_ORDER,
  SOURCE_LABELS,
  findDaySchedule,
  formatTimeRange,
  getTodayDayKey,
  sortBlocks,
} from "./utils";

type EditorMode = "create" | "edit";

interface BlockEditorState {
  mode: EditorMode;
  blockId?: string;
  form: ScheduleBlockWriteRequest;
}

const defaultBlockForm = (dayOfWeek = getTodayDayKey()): ScheduleBlockWriteRequest => ({
  dayOfWeek,
  startTime: "09:00",
  endTime: "10:00",
  activity: "",
  category: "WORK",
  note: "",
});

const buildEditorFromBlock = (
  block: ScheduleBlockResponse,
  dayOfWeek: ScheduleBlockWriteRequest["dayOfWeek"]
): BlockEditorState => ({
  mode: "edit",
  blockId: block.id,
  form: {
    dayOfWeek,
    startTime: block.startTime.slice(0, 5),
    endTime: block.endTime.slice(0, 5),
    activity: block.activity,
    category: block.category,
    note: block.note ?? "",
  },
});

const hasSchedulePattern = (value: string) =>
  /(월|화|수|목|금|토|일|monday|tuesday|wednesday|thursday|friday|saturday|sunday|\d{1,2}:\d{2})/i.test(
    value
  );

export const ScheduleWorkspace = () => {
  const schedule = useWeekSchedule();
  const [editor, setEditor] = useState<BlockEditorState | null>(null);
  const [importText, setImportText] = useState("");
  const [replaceExisting, setReplaceExisting] = useState(true);
  const [feedback, setFeedback] = useState<string | null>(null);

  const totalBlocks = schedule.data?.week.reduce((sum, day) => sum + day.blocks.length, 0) ?? 0;
  const manualBlocks =
    schedule.data?.week.flatMap((day) => day.blocks).filter((block) => block.sourceType === "MANUAL").length ?? 0;
  const importedBlocks =
    schedule.data?.week.flatMap((day) => day.blocks).filter((block) => block.sourceType === "GEMMA_IMPORT").length ?? 0;
  const todayBlocks = useMemo(
    () => sortBlocks(findDaySchedule(schedule.data, getTodayDayKey()).blocks),
    [schedule.data]
  );

  const weekDays = DAY_ORDER.map((dayKey) => {
    const day = findDaySchedule(schedule.data, dayKey);
    return {
      ...day,
      blocks: sortBlocks(day.blocks),
    };
  });

  const statusMessage =
    feedback ?? schedule.error ?? "텍스트 일정 가져오기, 블록 추가, 수정 결과가 이곳에 표시됩니다.";

  const handleEditorSubmit = async () => {
    if (!editor) {
      return;
    }

    if (!editor.form.activity.trim()) {
      setFeedback("일정 이름을 입력해 주세요.");
      return;
    }

    if (editor.form.startTime === editor.form.endTime) {
      setFeedback("시작 시간과 종료 시간이 같을 수 없습니다.");
      return;
    }

    try {
      if (editor.mode === "create") {
        await schedule.createBlock({
          ...editor.form,
          activity: editor.form.activity.trim(),
          note: editor.form.note?.trim() ? editor.form.note.trim() : null,
        });
        setFeedback("새 일정 블록을 추가했습니다.");
      } else if (editor.blockId) {
        await schedule.updateBlock(editor.blockId, {
          ...editor.form,
          activity: editor.form.activity.trim(),
          note: editor.form.note?.trim() ? editor.form.note.trim() : null,
        });
        setFeedback("일정 블록을 수정했습니다.");
      }

      setEditor(null);
    } catch (error) {
      setFeedback(getApiErrorMessage(error, "일정 블록을 저장하지 못했습니다."));
    }
  };

  const handleDelete = async () => {
    if (!editor?.blockId) {
      return;
    }

    try {
      await schedule.deleteBlock(editor.blockId);
      setFeedback("일정 블록을 삭제했습니다.");
      setEditor(null);
    } catch (error) {
      setFeedback(getApiErrorMessage(error, "일정 블록을 삭제하지 못했습니다."));
    }
  };

  const handleImport = async () => {
    const trimmed = importText.trim();

    if (!trimmed) {
      setFeedback("분석할 텍스트를 먼저 입력해 주세요.");
      return;
    }

    if (!hasSchedulePattern(trimmed)) {
      setFeedback("요일이나 시간 정보가 포함된 일정 형식으로 입력해 주세요.");
      return;
    }

    try {
      await schedule.importSchedule({ rawText: trimmed, replaceExisting });
      setFeedback("텍스트 분석이 끝났습니다. 시간표를 새로 반영했습니다.");
    } catch (error) {
      setFeedback(getApiErrorMessage(error, "일정을 자동으로 반영하지 못했습니다."));
    }
  };

  return (
    <div className="space-y-6">
      <section className="surface-card p-6 sm:p-8">
        <div className="flex flex-col gap-6 xl:flex-row xl:items-start xl:justify-between">
          <div className="max-w-3xl space-y-3">
            <p className="metric-label">시간표</p>
            <h1 className="text-3xl font-extrabold tracking-tight text-[var(--foreground)] sm:text-4xl">
              이번 주 시간표
            </h1>
            <p className="max-w-2xl text-base leading-7 text-[var(--foreground-muted)]">
              요일별 블록을 바로 읽고 수정할 수 있도록 구성했습니다. 추가, 수정, 자동 가져오기를 모두 한 화면에서 처리합니다.
            </p>
          </div>

          <div className="flex flex-wrap gap-3">
            <button type="button" onClick={() => void schedule.refresh()} className="btn-secondary">
              <RefreshCw className={`h-4 w-4 ${schedule.isLoading ? "animate-spin" : ""}`} />
              동기화
            </button>
            <button
              type="button"
              onClick={() => {
                setEditor({ mode: "create", form: defaultBlockForm() });
                setFeedback(null);
              }}
              className="btn-primary"
            >
              <Plus className="h-4 w-4" />
              새 일정 추가
            </button>
          </div>
        </div>
      </section>

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <article className="metric-card">
          <p className="metric-label">전체 블록</p>
          <p className="text-3xl font-extrabold tracking-tight text-[var(--foreground)]">{totalBlocks}</p>
          <p className="text-sm text-[var(--foreground-muted)]">이번 주에 등록된 일정 수</p>
        </article>
        <article className="metric-card">
          <p className="metric-label">직접 편집</p>
          <p className="text-3xl font-extrabold tracking-tight text-[var(--foreground)]">{manualBlocks}</p>
          <p className="text-sm text-[var(--foreground-muted)]">수동으로 추가하거나 수정한 블록</p>
        </article>
        <article className="metric-card">
          <p className="metric-label">자동 반영</p>
          <p className="text-3xl font-extrabold tracking-tight text-[var(--foreground)]">{importedBlocks}</p>
          <p className="text-sm text-[var(--foreground-muted)]">텍스트에서 가져온 블록</p>
        </article>
        <article className="metric-card">
          <p className="metric-label">오늘 일정</p>
          <p className="text-3xl font-extrabold tracking-tight text-[var(--foreground)]">{todayBlocks.length}</p>
          <p className="text-sm text-[var(--foreground-muted)]">오늘 확인할 블록 수</p>
        </article>
      </div>

      <section className="grid gap-4 lg:grid-cols-2 2xl:grid-cols-3">
        {weekDays.map((day) => {
          const isToday = day.dayOfWeek === getTodayDayKey();

          return (
            <article
              key={day.dayOfWeek}
              className={`surface-card p-0 ${isToday ? "border-[rgba(245,92,18,0.28)]" : ""}`}
            >
              <div className="flex items-center justify-between border-b border-[var(--border)] px-5 py-4">
                <div>
                  <p className="metric-label">{isToday ? "오늘" : "요일"}</p>
                  <h2 className="mt-1 text-xl font-bold text-[var(--foreground)]">
                    {DAY_FULL_LABELS[day.dayOfWeek]}
                  </h2>
                </div>
                <div className="flex items-center gap-2">
                  <span className="status-badge">{day.blocks.length}개</span>
                  <button
                    type="button"
                    className="icon-button"
                    aria-label={`${DAY_FULL_LABELS[day.dayOfWeek]} 일정 추가`}
                    onClick={() => {
                      setEditor({ mode: "create", form: defaultBlockForm(day.dayOfWeek) });
                      setFeedback(null);
                    }}
                  >
                    <Plus className="h-4 w-4" />
                  </button>
                </div>
              </div>

              <div className="space-y-3 px-5 py-5">
                {day.blocks.length > 0 ? (
                  day.blocks.map((block) => (
                    <button
                      key={block.id}
                      type="button"
                      onClick={() => {
                        setEditor(buildEditorFromBlock(block, day.dayOfWeek));
                        setFeedback(null);
                      }}
                      className="w-full rounded-2xl border border-[var(--border)] bg-[var(--surface-raised)] p-4 text-left hover:border-[rgba(245,92,18,0.28)]"
                    >
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="status-badge">{formatTimeRange(block.startTime, block.endTime)}</span>
                        <span className="status-badge">{CATEGORY_LABELS[block.category]}</span>
                        <span className="status-badge">{SOURCE_LABELS[block.sourceType]}</span>
                      </div>
                      <h3 className="mt-3 text-base font-bold text-[var(--foreground)]">{block.activity}</h3>
                      {block.note && (
                        <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">{block.note}</p>
                      )}
                    </button>
                  ))
                ) : (
                  <div className="rounded-2xl border border-dashed border-[var(--border)] bg-[var(--surface-muted)] p-6 text-sm text-[var(--foreground-muted)]">
                    아직 등록된 일정이 없습니다.
                  </div>
                )}
              </div>
            </article>
          );
        })}
      </section>

      <section className="grid gap-6 xl:grid-cols-[minmax(0,1.1fr)_360px]">
        <article className="surface-card p-6 sm:p-8">
          <div className="space-y-3">
            <p className="metric-label">텍스트 가져오기</p>
            <h2 className="text-2xl font-bold tracking-tight text-[var(--foreground)]">
              텍스트로 일정 반영
            </h2>
            <p className="max-w-2xl text-sm leading-6 text-[var(--foreground-muted)]">
              요일과 시간 정보가 들어간 텍스트를 붙여 넣으면 자동으로 일정 블록으로 정리합니다.
            </p>
          </div>

          <div className="mt-6 space-y-4">
            <textarea
              value={importText}
              onChange={(event) => setImportText(event.target.value)}
              className="textarea-field min-h-[180px] max-h-[220px] font-mono text-sm"
              placeholder={"월 09:00-11:00 기획 정리\n월 11:00-12:00 이동\n화 20:00-22:00 영어 학습"}
            />

            <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
              <label className="flex items-center gap-3 text-sm text-[var(--foreground-muted)]">
                <input
                  type="checkbox"
                  checked={replaceExisting}
                  onChange={(event) => setReplaceExisting(event.target.checked)}
                  className="h-4 w-4 rounded border-[var(--border)] accent-[var(--accent)]"
                />
                기존 일정 대신 새로 반영
              </label>

              <button type="button" onClick={() => void handleImport()} disabled={schedule.isMutating} className="btn-primary">
                {schedule.isMutating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
                지능형 분석 및 반영
              </button>
            </div>
          </div>
        </article>

        <aside className="surface-card p-6 sm:p-8">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-[rgba(245,92,18,0.1)] text-[var(--accent)]">
              <CalendarRange className="h-5 w-5" />
            </div>
            <div>
              <p className="metric-label">상태</p>
              <h3 className="mt-1 text-xl font-bold text-[var(--foreground)]">가져오기 상태</h3>
            </div>
          </div>

          <p className="mt-5 text-sm leading-7 text-[var(--foreground-muted)]">{statusMessage}</p>
        </aside>
      </section>

      {editor && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/35 px-4 py-6">
          <div className="surface-card w-full max-w-2xl p-6 sm:p-8">
            <div className="flex items-start justify-between gap-4">
              <div>
                <p className="metric-label">{editor.mode === "create" ? "새 일정" : "일정 수정"}</p>
                <h2 className="mt-2 text-2xl font-bold tracking-tight text-[var(--foreground)]">
                  {editor.mode === "create" ? "새 일정 블록 추가" : "일정 블록 수정"}
                </h2>
              </div>
              <button type="button" onClick={() => setEditor(null)} className="icon-button" aria-label="닫기">
                <Plus className="h-4 w-4 rotate-45" />
              </button>
            </div>

            <div className="mt-6 grid gap-4 md:grid-cols-2">
              <div>
                <label className="field-label" htmlFor="schedule-day">
                  요일
                </label>
                <select
                  id="schedule-day"
                  className="select-field mt-2"
                  value={editor.form.dayOfWeek}
                  onChange={(event) =>
                    setEditor((current) =>
                      current
                        ? {
                            ...current,
                            form: {
                              ...current.form,
                              dayOfWeek: event.target.value as ScheduleBlockWriteRequest["dayOfWeek"],
                            },
                          }
                        : current
                    )
                  }
                >
                  {DAY_ORDER.map((day) => (
                    <option key={day} value={day}>
                      {DAY_FULL_LABELS[day]}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="field-label" htmlFor="schedule-category">
                  카테고리
                </label>
                <select
                  id="schedule-category"
                  className="select-field mt-2"
                  value={editor.form.category}
                  onChange={(event) =>
                    setEditor((current) =>
                      current
                        ? {
                            ...current,
                            form: {
                              ...current.form,
                              category: event.target.value as ScheduleBlockWriteRequest["category"],
                            },
                          }
                        : current
                    )
                  }
                >
                  {Object.entries(CATEGORY_LABELS).map(([value, label]) => (
                    <option key={value} value={value}>
                      {label}
                    </option>
                  ))}
                </select>
              </div>

              <div>
                <label className="field-label" htmlFor="schedule-start">
                  시작 시간
                </label>
                <input
                  id="schedule-start"
                  className="input-field mt-2"
                  type="time"
                  value={editor.form.startTime}
                  onChange={(event) =>
                    setEditor((current) =>
                      current
                        ? {
                            ...current,
                            form: {
                              ...current.form,
                              startTime: event.target.value,
                            },
                          }
                        : current
                    )
                  }
                />
              </div>

              <div>
                <label className="field-label" htmlFor="schedule-end">
                  종료 시간
                </label>
                <input
                  id="schedule-end"
                  className="input-field mt-2"
                  type="time"
                  value={editor.form.endTime}
                  onChange={(event) =>
                    setEditor((current) =>
                      current
                        ? {
                            ...current,
                            form: {
                              ...current.form,
                              endTime: event.target.value,
                            },
                          }
                        : current
                    )
                  }
                />
              </div>
            </div>

            <div className="mt-4">
              <label className="field-label" htmlFor="schedule-activity">
                일정 이름
              </label>
              <input
                id="schedule-activity"
                className="input-field mt-2"
                value={editor.form.activity}
                onChange={(event) =>
                  setEditor((current) =>
                    current
                      ? {
                          ...current,
                          form: {
                            ...current.form,
                            activity: event.target.value,
                          },
                        }
                      : current
                  )
                }
                placeholder="예: 기획 회의"
              />
            </div>

            <div className="mt-4">
              <label className="field-label" htmlFor="schedule-note">
                메모
              </label>
              <textarea
                id="schedule-note"
                className="textarea-field mt-2"
                value={editor.form.note ?? ""}
                onChange={(event) =>
                  setEditor((current) =>
                    current
                      ? {
                          ...current,
                          form: {
                            ...current.form,
                            note: event.target.value,
                          },
                        }
                      : current
                  )
                }
                placeholder="필요한 준비 사항이나 메모를 적어 주세요."
              />
            </div>

            <div className="mt-6 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              {editor.mode === "edit" ? (
                <button
                  type="button"
                  onClick={() => void handleDelete()}
                  disabled={schedule.isMutating}
                  className="btn-ghost justify-center border border-[var(--border)] text-[var(--error)]"
                >
                  <Trash2 className="h-4 w-4" />
                  삭제
                </button>
              ) : (
                <span />
              )}

              <div className="flex flex-col gap-3 sm:flex-row">
                <button type="button" onClick={() => setEditor(null)} className="btn-secondary">
                  취소
                </button>
                <button type="button" onClick={() => void handleEditorSubmit()} disabled={schedule.isMutating} className="btn-primary">
                  {schedule.isMutating ? <Loader2 className="h-4 w-4 animate-spin" /> : <Pencil className="h-4 w-4" />}
                  {editor.mode === "create" ? "일정 추가" : "변경 저장"}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
