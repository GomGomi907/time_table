"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

import { useOnboardingBootstrap } from "@/hooks/use-onboarding-bootstrap";
import { useSessionBootstrap } from "@/hooks/use-session-bootstrap";
import { api } from "@/lib/api";
import { formatDateTime, formatServiceCopy } from "@/lib/format";
import { writeOnboardingDayHandoff } from "@/lib/onboarding-day-handoff";
import { CATEGORY_LABELS } from "@/lib/schedule";
import { OnboardingQuestion, OnboardingStatus } from "@/lib/types";
import { useAppStore } from "@/stores/app-store";

type AsyncPhase = "idle" | "loading" | "ready" | "error";

const EMPTY_IMPORT_SUMMARY = {
  calendarEventCount: 0,
  taskCount: 0,
  scheduleBlockCount: 0,
  goalCount: 0,
  lastCalendarSyncAt: null,
  lastTaskSyncAt: null,
  workspaceSummary: "아직 가져온 데이터가 많지 않아 생활 리듬 기준을 먼저 바탕으로 잡겠습니다.",
  sourceLabel: "가져온 데이터가 적어도 생활 리듬 기준만 있으면 첫 조정안을 충분히 만들 수 있습니다.",
} as const;

const QUESTION_GROUPS = [
  {
    id: "morning",
    title: "평일 시작 기준",
    description: "오전 블록을 잡을 때 먼저 지켜야 할 리듬입니다.",
    questionIds: ["wakeTime", "workStartTime"],
  },
  {
    id: "evening",
    title: "저녁과 마감 기준",
    description: "회복 시간과 수면 구간을 무리하게 침범하지 않도록 쓰입니다.",
    questionIds: ["dinnerTime", "sleepTime"],
  },
  {
    id: "weekend",
    title: "주말 한 칸의 성향",
    description: "주말 블록을 회복형으로 둘지, 몰입형으로 둘지 결정합니다.",
    questionIds: ["weekendStyle"],
  },
  {
    id: "focus",
    title: "집중 실행 기준",
    description: "추천 할 일, 전환 여유, 조정안 제안 빈도에 쓰이는 실행 기준입니다.",
    questionIds: ["focusSessionMinutes", "focusBreakMinutes", "focusInterventionStyle"],
  },
] as const;

function profileAnswerValue(status: OnboardingStatus | null, questionId: string) {
  const profile = status?.profile;
  if (!profile) {
    return undefined;
  }

  switch (questionId) {
    case "wakeTime":
      return profile.wakeTime;
    case "workStartTime":
      return profile.workStartTime;
    case "dinnerTime":
      return profile.dinnerTime;
    case "sleepTime":
      return profile.sleepTime;
    case "weekendStyle":
      return profile.weekendStyle;
    case "focusSessionMinutes":
      return profile.focusSessionMinutes;
    case "focusBreakMinutes":
      return profile.focusBreakMinutes;
    case "focusInterventionStyle":
      return profile.focusInterventionStyle;
    default:
      return undefined;
  }
}

function optionLabel(question: OnboardingQuestion, value: string | undefined) {
  return question.options.find((option) => option.value === value)?.label ?? value ?? "미선택";
}

export function OnboardingView() {
  const router = useRouter();
  const { session, sessionPhase, refreshSession } = useSessionBootstrap();
  const {
    onboardingPhase,
    onboardingStatus,
    onboardingError,
    onboardingCompleted,
    refreshOnboarding,
    applyOnboardingStatus,
  } = useOnboardingBootstrap();
  const showNotice = useAppStore((state) => state.showNotice);

  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [bootstrapPhase, setBootstrapPhase] = useState<AsyncPhase>("idle");
  const [bootstrapMessage, setBootstrapMessage] = useState<string | null>(null);
  const [submitPhase, setSubmitPhase] = useState<AsyncPhase>("idle");
  const [submitMessage, setSubmitMessage] = useState<string | null>(null);
  const [completionPhase, setCompletionPhase] = useState<AsyncPhase>("idle");
  const [isEditingAnswers, setIsEditingAnswers] = useState(false);

  const questions = useMemo(() => onboardingStatus?.questions ?? [], [onboardingStatus?.questions]);
  const importSummary = onboardingStatus?.importSummary ?? EMPTY_IMPORT_SUMMARY;
  const displayName = onboardingStatus?.displayName || session?.displayName || "새 사용자";
  const onboardingTimeZone = onboardingStatus?.timezone || session?.timezone;
  const suggestion = onboardingStatus?.experience?.suggestion ?? null;
  const previewItems = onboardingStatus?.experience?.previewItems ?? [];
  const suggestionId = suggestion?.id;

  const groupedQuestions = useMemo(
    () =>
      QUESTION_GROUPS.map((group) => ({
        ...group,
        questions: group.questionIds
          .map((questionId) => questions.find((question) => question.id === questionId))
          .filter((question): question is OnboardingQuestion => Boolean(question)),
      })).filter((group) => group.questions.length > 0),
    [questions],
  );

  useEffect(() => {
    if (sessionPhase === "ready" && !session?.authenticated) {
      router.replace("/login");
    }
  }, [router, session?.authenticated, sessionPhase]);

  useEffect(() => {
    if (onboardingCompleted && onboardingPhase === "ready") {
      router.replace("/dashboard");
    }
  }, [router, onboardingCompleted, onboardingPhase]);

  useEffect(() => {
    if (!onboardingStatus) {
      return;
    }

    setAnswers((current) => {
      const next = { ...current };
      for (const question of questions) {
        const existing = current[question.id];
        const profileValue = profileAnswerValue(onboardingStatus, question.id);
        next[question.id] = existing ?? profileValue ?? question.options[0]?.value ?? "";
      }
      return next;
    });
  }, [onboardingStatus, questions]);

  useEffect(() => {
    if (!onboardingStatus?.profileReady) {
      return;
    }

    setIsEditingAnswers(false);
  }, [onboardingStatus?.profileReady]);

  useEffect(() => {
    if (
      !session?.authenticated ||
      onboardingPhase !== "ready" ||
      !onboardingStatus ||
      onboardingCompleted ||
      onboardingStatus.nextStep !== "bootstrap" ||
      bootstrapPhase !== "idle"
    ) {
      return;
    }

    async function runBootstrap() {
      try {
        setBootstrapPhase("loading");
        const response = await api.bootstrapOnboarding();
        applyOnboardingStatus(response.status);
        setBootstrapMessage(response.message);
        setBootstrapPhase("ready");
      } catch (error) {
        setBootstrapPhase("error");
        setBootstrapMessage(
          error instanceof Error ? error.message : "초기 데이터 가져오기에 실패했습니다.",
        );
      }
    }

    void runBootstrap();
  }, [
    session?.authenticated,
    onboardingPhase,
    onboardingStatus,
    onboardingCompleted,
    bootstrapPhase,
    applyOnboardingStatus,
  ]);

  const answerSummary = useMemo(
    () =>
      questions.map((question) => ({
        id: question.id,
        title: question.title,
        value: optionLabel(question, answers[question.id]),
      })),
    [answers, questions],
  );

  const answeredCount = questions.filter((question) => Boolean(answers[question.id])).length;
  const canSubmitAnswers = questions.every((question) => Boolean(answers[question.id]));

  async function handleBootstrapRetry() {
    try {
      setBootstrapPhase("loading");
      const response = await api.bootstrapOnboarding();
      applyOnboardingStatus(response.status);
      setBootstrapMessage(response.message);
      setBootstrapPhase("ready");
    } catch (error) {
      setBootstrapPhase("error");
      setBootstrapMessage(
        error instanceof Error ? error.message : "초기 데이터 가져오기에 실패했습니다.",
      );
    }
  }

  async function handleSubmitAnswers() {
    if (!canSubmitAnswers) {
      return;
    }

    try {
      setSubmitPhase("loading");
      const response = await api.saveOnboardingAnswers({ answers });
      applyOnboardingStatus(response.status);
      setSubmitMessage(response.message);
      setSubmitPhase("ready");
      setIsEditingAnswers(false);
      showNotice({
        tone: "success",
        title: "첫 일정 조정안을 준비했습니다.",
        detail: "지금 답변을 기준으로 바로 시작할 수 있는 첫 조정안을 확인해 보세요.",
      });
    } catch (error) {
      setSubmitPhase("error");
      setSubmitMessage(
        error instanceof Error ? error.message : "처음 설정 답변 저장에 실패했습니다.",
      );
      showNotice({
        tone: "error",
        title: "생활 리듬 저장에 실패했습니다.",
        detail: error instanceof Error ? error.message : "잠시 후 다시 시도해 주세요.",
      });
    }
  }

  async function handleComplete(applySuggestion: boolean) {
    try {
      setCompletionPhase("loading");
      const response = await api.completeOnboarding({
        applySuggestion,
        suggestionId: applySuggestion ? suggestionId : undefined,
      });
      applyOnboardingStatus(response.status);

      if (session?.userId) {
        const handoffSuggestion = response.appliedSuggestion ?? suggestion;
        writeOnboardingDayHandoff(session.userId, {
          appliedSuggestion: Boolean(applySuggestion && response.appliedSuggestion),
          suggestionSummary: handoffSuggestion?.summary ?? null,
          suggestionExplanation:
            handoffSuggestion?.explanation ?? onboardingStatus?.experience?.summary ?? null,
          answers: answerSummary,
        });
      }

      await Promise.all([refreshSession(), refreshOnboarding()]);
      showNotice({
        tone: "success",
        title: applySuggestion ? "첫 조정안을 반영했습니다." : "처음 설정을 마쳤습니다.",
        detail: response.message,
      });
      router.replace("/dashboard");
    } catch (error) {
      setCompletionPhase("error");
      showNotice({
        tone: "error",
        title: "처음 설정 완료 처리에 실패했습니다.",
        detail: error instanceof Error ? error.message : "잠시 후 다시 시도해 주세요.",
      });
    }
  }

  if (
    sessionPhase !== "ready" ||
    onboardingPhase === "idle" ||
    onboardingPhase === "loading" ||
    !onboardingStatus
  ) {
    return (
      <div className="status-screen">
        <div className="status-panel">
          <p className="eyebrow">처음 설정</p>
          <h1>첫 일정 조정을 준비하고 있습니다.</h1>
          <p>로그인 상태와 설정 단계를 확인한 뒤, 필요한 질문과 첫 조정안 화면으로 이어집니다.</p>
        </div>
      </div>
    );
  }

  if (onboardingPhase === "error") {
    return (
      <div className="status-screen">
        <div className="status-panel">
          <p className="eyebrow">처음 설정</p>
          <h1>처음 설정 상태를 불러오지 못했습니다.</h1>
          <p>{onboardingError ?? "잠시 후 다시 시도해 주세요."}</p>
          <button className="solid-btn" onClick={() => void refreshOnboarding()}>
            다시 시도
          </button>
        </div>
      </div>
    );
  }

  if (onboardingCompleted) {
    return null;
  }

  const isBootstrapping = onboardingStatus.nextStep === "bootstrap" || bootstrapPhase === "loading";
  const isQuestionStage = !isBootstrapping && (!onboardingStatus.profileReady || isEditingAnswers);

  if (isBootstrapping) {
    return (
      <div className="status-screen onboarding-shell">
        <div className="onboarding-loading-panel">
          <section className="onboarding-loading-copy">
            <p className="eyebrow">일정 조율 준비</p>
            <h1>이번 주 일정을 먼저 읽고 있습니다.</h1>
            <p>
              연결 상태를 다시 묻기보다, 이번 주 일정과 할 일에서 빈 시간과 리듬을 먼저 읽고
              있습니다. 잠시 뒤 캘린더에 잘 남지 않는 생활 리듬만 짧게 확인할게요.
            </p>
            <div className="loading-dots" aria-hidden="true">
              <span />
              <span />
              <span />
            </div>
            <div className="onboarding-loading-points">
              <div className="onboarding-loading-point">
                <strong>지금 하는 일</strong>
                <span>캘린더와 할 일을 묶어 이번 주 일정을 먼저 파악하고 있습니다.</span>
              </div>
              <div className="onboarding-loading-point">
                <strong>곧 확인할 기준</strong>
                <span>평일 시작, 저녁 마감, 주말 한 칸처럼 캘린더 밖의 리듬만 묻습니다.</span>
              </div>
              <div className="onboarding-loading-point">
                <strong>마지막 단계</strong>
                <span>답변이 들어오면 첫 일정 조정안을 만들어 보여드립니다.</span>
              </div>
            </div>
            {bootstrapMessage ? <p className="micro-copy">{bootstrapMessage}</p> : null}
            {bootstrapPhase === "error" ? (
              <div className="guest-actions">
                <button className="solid-btn" onClick={() => void handleBootstrapRetry()}>
                  다시 읽기
                </button>
                <button className="ghost-btn" onClick={() => void refreshOnboarding()}>
                  상태 새로고침
                </button>
              </div>
            ) : null}
          </section>

          <aside className="onboarding-loading-stats">
            <article className="surface-card onboarding-stat-card">
              <strong>가져온 일정</strong>
              <span>{importSummary.calendarEventCount}건</span>
            </article>
            <article className="surface-card onboarding-stat-card">
              <strong>가져온 할 일</strong>
              <span>{importSummary.taskCount}건</span>
            </article>
            <article className="surface-card onboarding-stat-card">
              <strong>기본 루틴 블록</strong>
              <span>{importSummary.scheduleBlockCount}건</span>
            </article>
            <article className="surface-card onboarding-stat-card">
              <strong>최근 캘린더 확인</strong>
              <span>{formatDateTime(importSummary.lastCalendarSyncAt, onboardingTimeZone)}</span>
            </article>
            <article className="surface-card onboarding-stat-card onboarding-stat-card-wide">
              <strong>현재 확인한 내용</strong>
              <span>{importSummary.workspaceSummary}</span>
            </article>
          </aside>
        </div>
      </div>
    );
  }

  if (isQuestionStage) {
    return (
      <div className="status-screen onboarding-shell">
        <div className="onboarding-panel onboarding-panel-wide">
          <section className="onboarding-sidebar onboarding-sidebar-compact">
            <p className="eyebrow">생활 리듬 설정</p>
            <h1>캘린더에 없는 생활 리듬만 알려 주세요.</h1>
            <p>
              이미 읽은 일정과 할 일은 그대로 두고, 캘린더에 잘 적지 않는 생활 리듬만 짧게 정리합니다.
              이 기준이 이후 조정안을 만들 때 먼저 지킬 출발점이 됩니다.
            </p>

            <div className="onboarding-step-list">
              <div className="onboarding-step-item completed">
                <strong>1. 이번 주 데이터 읽기</strong>
                <span>{bootstrapMessage ?? "캘린더와 할 일을 먼저 확인했습니다."}</span>
              </div>
              <div className="onboarding-step-item active">
                <strong>2. 생활 리듬 설정</strong>
                <span>평일 시작, 저녁 마감, 주말 성향만 짧게 맞춥니다.</span>
              </div>
              <div className="onboarding-step-item">
                <strong>3. 첫 일정 조정안 확인</strong>
                <span>답변을 반영한 실제 루틴 후보를 바로 보여드립니다.</span>
              </div>
            </div>

            <div className="onboarding-overview-list">
              <div className="onboarding-overview-item">
                <strong>이미 확인한 것</strong>
                <span>{importSummary.workspaceSummary}</span>
              </div>
              <div className="onboarding-overview-item">
                <strong>이번에 정할 기준</strong>
                <span>평일 아침, 저녁 회복 구간, 주말 한 칸의 기본 성향</span>
              </div>
              <div className="onboarding-overview-item">
                <strong>현재 데이터 범위</strong>
                <span>
                  일정 {importSummary.calendarEventCount}건 · 할 일 {importSummary.taskCount}건
                </span>
              </div>
            </div>
          </section>

          <section className="onboarding-main">
            <article className="surface-card onboarding-card onboarding-question-card">
              <div className="onboarding-stage-head">
                <div>
                  <p className="eyebrow">일정 조정 기준 질문</p>
                  <h2>캘린더 밖의 생활 패턴만 빠르게 맞춥니다.</h2>
                  <p className="section-header-note">
                    긴 설정 화면이 아니라, 일정을 조정할 때 먼저 지켜야 할 기준만 정리합니다.
                  </p>
                </div>
                <span className="accent-pill">
                  {answeredCount} / {questions.length} 답변
                </span>
              </div>

              <section className="onboarding-guidance-card">
                <strong>이 답변으로 달라지는 점</strong>
                <div className="onboarding-guidance-list">
                  <span>업무 시작 전 준비 시간과 이동 시간을 먼저 보호합니다.</span>
                  <span>저녁 식사와 수면 구간을 우선 지켜 무리한 야간 배치를 피합니다.</span>
                  <span>주말 한 칸도 회복형인지 몰입형인지에 맞춰 제안 강도를 조절합니다.</span>
                </div>
              </section>

              <p className="onboarding-mobile-default-note">
                모바일에서는 하루 시작과 끝만 먼저 정합니다. 업무 시작, 저녁, 주말, 집중 기준은 기본값으로 시작하고 나중에 바꿀 수 있습니다.
              </p>

              <div className="onboarding-question-groups">
                {groupedQuestions.map((group) => (
                  <section key={group.id} className="onboarding-group-card">
                    <div className="onboarding-group-head">
                      <strong>{group.title}</strong>
                      <span>{group.description}</span>
                    </div>

                    {group.questions.map((question) => (
                      <section key={question.id} className="onboarding-question-block">
                        <div className="onboarding-question-head">
                          <strong>{question.title}</strong>
                          <span>{question.description}</span>
                        </div>
                        <div className="onboarding-option-grid">
                          {question.options.map((option) => {
                            const selected = answers[question.id] === option.value;
                            return (
                              <button
                                key={option.value}
                                className={`onboarding-option-card ${selected ? "selected" : ""}`}
                                type="button"
                                onClick={() =>
                                  setAnswers((current) => ({
                                    ...current,
                                    [question.id]: option.value,
                                  }))
                                }
                              >
                                <strong>{option.label}</strong>
                                <span>{option.helper}</span>
                              </button>
                            );
                          })}
                        </div>
                      </section>
                    ))}
                  </section>
                ))}
              </div>

              <div className="onboarding-step-actions">
                <div className="onboarding-inline-note">
                  <strong>현재 읽은 인상</strong>
                  <span>{importSummary.sourceLabel}</span>
                </div>
                <button
                  className="solid-btn"
                  disabled={!canSubmitAnswers || submitPhase === "loading"}
                  onClick={() => void handleSubmitAnswers()}
                  type="button"
                >
                  {submitPhase === "loading" ? "조정안을 만드는 중..." : "첫 일정 조정안 만들기"}
                </button>
              </div>

              {submitMessage ? <p className="micro-copy">{submitMessage}</p> : null}
            </article>
          </section>
        </div>
      </div>
    );
  }

  return (
    <div className="status-screen onboarding-shell">
      <div className="onboarding-panel onboarding-panel-wide">
        <section className="onboarding-sidebar onboarding-sidebar-compact">
          <p className="eyebrow">첫 일정 조정안</p>
          <h1>이제 이번 주 일정을 바로 시작할 수 있습니다.</h1>
          <p>
            설정만 저장한 것이 아니라, 지금 답변과 가져온 데이터를 바탕으로 바로 실행 가능한
            첫 조정안을 만들었습니다.
          </p>

          <div className="onboarding-step-list">
            <div className="onboarding-step-item completed">
              <strong>1. 이번 주 데이터 읽기</strong>
              <span>{bootstrapMessage ?? "캘린더와 할 일을 확인했습니다."}</span>
            </div>
            <div className="onboarding-step-item completed">
              <strong>2. 생활 리듬 설정</strong>
              <span>평일 시작, 저녁 마감, 주말 성향 기준을 저장했습니다.</span>
            </div>
            <div className="onboarding-step-item active">
              <strong>3. 첫 일정 조정안 확인</strong>
              <span>원하면 지금 바로 적용하고 오늘 브리핑으로 이어질 수 있습니다.</span>
            </div>
          </div>

          <div className="onboarding-answer-summary">
            {answerSummary.map((item) => (
              <div key={item.id} className="onboarding-answer-chip">
                <strong>{formatServiceCopy(item.title)}</strong>
                <span>{item.value}</span>
              </div>
            ))}
          </div>

          <div className="onboarding-overview-list">
            <div className="onboarding-overview-item">
              <strong>현재 확인한 내용</strong>
              <span>{importSummary.workspaceSummary}</span>
            </div>
            <div className="onboarding-overview-item">
              <strong>최근 캘린더 확인</strong>
              <span>{formatDateTime(importSummary.lastCalendarSyncAt, onboardingTimeZone)}</span>
            </div>
            <div className="onboarding-overview-item">
              <strong>최근 할 일 확인</strong>
              <span>{formatDateTime(importSummary.lastTaskSyncAt, onboardingTimeZone)}</span>
            </div>
          </div>
        </section>

        <section className="onboarding-main">
          <article className="surface-card onboarding-card onboarding-preview-card">
            <div className="onboarding-stage-head">
              <div>
                <p className="eyebrow">조정안 확인</p>
                <h2>이번 주를 이렇게 정리해 볼게요.</h2>
                <p className="section-header-note">
                  {suggestion?.explanation ??
                    onboardingStatus.experience?.summary ??
                    "지금은 기준만 저장하고 나중에 오늘 브리핑에서 다시 조정안을 받아도 됩니다."}
                </p>
              </div>
              <span className="accent-pill">
                {previewItems.length > 0 ? `${previewItems.length}개 루틴 후보` : "설명만 있는 제안"}
              </span>
            </div>

            <div className="onboarding-preview-hero">
              <div className="onboarding-highlight-card">
                <p className="panel-kicker">이번 조정안의 핵심</p>
                <strong>{suggestion?.summary ?? "기존 일정을 먼저 유지합니다."}</strong>
                <p>
                  {onboardingStatus.experience?.summary ??
                    "현재 일정과 겹치지 않도록 이번 주에 바로 체감될 수 있는 변화만 먼저 골랐습니다."}
                </p>
              </div>

              <div className="onboarding-highlight-card">
                <p className="panel-kicker">먼저 지킬 기준</p>
                <div className="onboarding-guidance-list">
                  <span>업무 시작 전에는 아침 준비와 이동 시간을 먼저 남겨 둡니다.</span>
                  <span>저녁 식사와 수면 구간을 우선 보호해 무리한 야간 배치를 피합니다.</span>
                  <span>주말 블록도 선택한 성향에 맞춰 회복형 또는 몰입형으로 잡습니다.</span>
                </div>
              </div>
            </div>

            {previewItems.length > 0 ? (
              <div className="onboarding-preview-list">
                {previewItems.map((item) => (
                  <div key={`${item.title}-${item.days}-${item.startTime}`} className="onboarding-preview-item">
                    <div className="onboarding-preview-copy">
                      <strong>{formatServiceCopy(item.title)}</strong>
                      <span>
                        {item.days} · {item.startTime.slice(0, 5)} - {item.endTime.slice(0, 5)}
                      </span>
                    </div>
                    <div className="onboarding-preview-meta">
                      <span className="accent-pill subtle">{CATEGORY_LABELS[item.category] ?? item.category}</span>
                      <p>{formatServiceCopy(item.reason)}</p>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <section className="surface-card empty-state subtle">
                <strong>이번 주는 기존 일정을 먼저 유지하는 편이 자연스럽습니다.</strong>
                <p>
                  새로운 루틴을 억지로 넣기보다, 현재 일정 위에서 시작한 뒤 오늘 브리핑에서 상황 변화가
                  생길 때 다시 조정안을 받는 편이 더 자연스럽습니다.
                </p>
              </section>
            )}

            <div className="onboarding-step-actions">
              <button
                className="ghost-btn"
                disabled={completionPhase === "loading"}
                onClick={() => setIsEditingAnswers(true)}
                type="button"
              >
                답변 다시 수정
              </button>

              <div className="board-head-actions">
                <button
                  className="ghost-btn"
                  disabled={completionPhase === "loading"}
                  onClick={() => void handleComplete(false)}
                  type="button"
                >
                  기준만 저장하고 둘러보기
                </button>
                {suggestionId && previewItems.length > 0 ? (
                  <button
                    className="solid-btn"
                    disabled={completionPhase === "loading"}
                    onClick={() => void handleComplete(true)}
                    type="button"
                  >
                    {completionPhase === "loading" ? "적용 중..." : "이 조정안으로 시작하기"}
                  </button>
                ) : null}
              </div>
            </div>
          </article>
        </section>
      </div>
    </div>
  );
}
