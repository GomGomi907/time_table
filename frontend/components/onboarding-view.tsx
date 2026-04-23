"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

import { useOnboardingBootstrap } from "@/hooks/use-onboarding-bootstrap";
import { useSessionBootstrap } from "@/hooks/use-session-bootstrap";
import { api } from "@/lib/api";
import { formatClockValue, formatDateTime } from "@/lib/format";
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
  workspaceSummary: "아직 가져온 데이터가 없어 생활 패턴 답변을 기준으로 먼저 시작합니다.",
  sourceLabel: "가져온 데이터가 일부 비어 있어도 답변 기반으로 첫 제안을 만들 수 있습니다.",
} as const;

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
  const questions = useMemo(() => onboardingStatus?.questions ?? [], [onboardingStatus?.questions]);
  const importSummary = onboardingStatus?.importSummary ?? EMPTY_IMPORT_SUMMARY;
  const displayName = onboardingStatus?.displayName || session?.displayName || "새 사용자";
  const onboardingTimeZone = onboardingStatus?.timezone || session?.timezone;

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
        setBootstrapMessage(error instanceof Error ? error.message : "초기 데이터 가져오기에 실패했습니다.");
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

  const previewItems = onboardingStatus?.experience?.previewItems ?? [];
  const suggestionId = onboardingStatus?.experience?.suggestion.id;
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
      setBootstrapMessage(error instanceof Error ? error.message : "초기 데이터 가져오기에 실패했습니다.");
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
      showNotice({
        tone: "success",
        title: "생활 패턴을 반영했습니다.",
        detail: "첫 AI 일정 조율안을 이어서 확인해 보세요.",
      });
    } catch (error) {
      setSubmitPhase("error");
      setSubmitMessage(error instanceof Error ? error.message : "온보딩 답변 저장에 실패했습니다.");
      showNotice({
        tone: "error",
        title: "답변 저장에 실패했습니다.",
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
      await Promise.all([refreshSession(), refreshOnboarding()]);
      showNotice({
        tone: "success",
        title: applySuggestion ? "첫 AI 제안을 반영했습니다." : "온보딩을 마쳤습니다.",
        detail: response.message,
      });
      router.replace("/dashboard");
    } catch (error) {
      setCompletionPhase("error");
      showNotice({
        tone: "error",
        title: "온보딩 완료 처리에 실패했습니다.",
        detail: error instanceof Error ? error.message : "잠시 후 다시 시도해 주세요.",
      });
    }
  }

  if (sessionPhase !== "ready" || onboardingPhase === "idle" || onboardingPhase === "loading" || !onboardingStatus) {
    return (
      <div className="status-screen">
        <div className="status-panel">
          <p className="eyebrow">AI Onboarding</p>
          <h1>첫 일정 조율 흐름을 준비하고 있습니다.</h1>
          <p>세션과 온보딩 상태를 확인한 뒤, 바로 질문과 첫 제안 화면으로 이어집니다.</p>
        </div>
      </div>
    );
  }

  if (onboardingPhase === "error") {
    return (
      <div className="status-screen">
        <div className="status-panel">
          <p className="eyebrow">AI Onboarding</p>
          <h1>온보딩 상태를 불러오지 못했습니다.</h1>
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
  const isQuestionStage = !isBootstrapping && !onboardingStatus.profileReady;

  if (isBootstrapping) {
    return (
      <div className="status-screen onboarding-shell">
        <div className="onboarding-loading-panel">
          <section className="onboarding-loading-copy">
            <p className="eyebrow">Loading Workspace</p>
            <h1>Google 데이터를 조용히 확인하고 있습니다.</h1>
            <p>
              화면에서 연결 확인은 생략하고, 가져올 수 있는 일정과 할 일을 먼저 읽은 뒤 생활 패턴 질문으로
              이어집니다.
            </p>
            <div className="loading-dots" aria-hidden="true">
              <span />
              <span />
              <span />
            </div>
            <div className="onboarding-loading-points">
              <div className="onboarding-loading-point">
                <strong>연결 상태</strong>
                <span>{onboardingStatus.googleConnected ? "Google 연결 확인됨" : "워크스페이스 기준으로 진행"}</span>
              </div>
              <div className="onboarding-loading-point">
                <strong>현재 신호</strong>
                <span>{importSummary.workspaceSummary}</span>
              </div>
              <div className="onboarding-loading-point">
                <strong>다음 단계</strong>
                <span>캘린더에 없는 고정 생활 패턴 5가지를 짧게 묻습니다.</span>
              </div>
            </div>
            {bootstrapMessage ? <p className="micro-copy">{bootstrapMessage}</p> : null}
            {bootstrapPhase === "error" ? (
              <div className="guest-actions">
                <button className="solid-btn" onClick={() => void handleBootstrapRetry()}>
                  다시 가져오기
                </button>
                <button className="ghost-btn" onClick={() => void refreshOnboarding()}>
                  상태 새로고침
                </button>
              </div>
            ) : null}
          </section>

          <aside className="onboarding-loading-stats">
            <article className="surface-card onboarding-stat-card">
              <strong>캘린더 일정</strong>
              <span>{importSummary.calendarEventCount}건</span>
            </article>
            <article className="surface-card onboarding-stat-card">
              <strong>할 일</strong>
              <span>{importSummary.taskCount}건</span>
            </article>
            <article className="surface-card onboarding-stat-card">
              <strong>현재 루틴</strong>
              <span>{importSummary.scheduleBlockCount}건</span>
            </article>
            <article className="surface-card onboarding-stat-card">
              <strong>최근 동기화</strong>
              <span>{formatDateTime(importSummary.lastCalendarSyncAt, onboardingTimeZone)}</span>
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
            <p className="eyebrow">Pattern Check</p>
            <h1>{displayName}님 일정에서 빠진 고정 패턴만 짧게 맞춥니다.</h1>
            <p>{importSummary.sourceLabel}</p>
            <div className="onboarding-step-list">
              <div className="onboarding-step-item completed">
                <strong>1. 가져오기 완료</strong>
                <span>{bootstrapMessage ?? "캘린더/태스크 상태를 확인했습니다."}</span>
              </div>
              <div className="onboarding-step-item active">
                <strong>2. 생활 패턴 질문</strong>
                <span>선택형 답변만 받고, 바로 첫 AI 제안으로 이어갑니다.</span>
              </div>
              <div className="onboarding-step-item">
                <strong>3. AI 일정 조율 미리보기</strong>
                <span>답변과 현재 데이터를 바탕으로 첫 주간 루틴을 바로 제안합니다.</span>
              </div>
            </div>
          </section>

          <section className="onboarding-main">
            <article className="surface-card onboarding-card onboarding-question-card">
              <div className="onboarding-stage-head">
                <div>
                  <p className="eyebrow">Questions</p>
                  <h2>캘린더 밖의 생활 패턴을 선택해 주세요.</h2>
                  <p className="section-header-note">
                    자유 입력 없이 고르면 됩니다. 이 답변이 이후 AI 재조율의 기준점이 됩니다.
                  </p>
                </div>
                <span className="accent-pill">5 questions</span>
              </div>

              <div className="onboarding-question-list">
                {questions.map((question) => (
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
              </div>

              <div className="onboarding-step-actions">
                <div className="onboarding-inline-note">
                  <strong>가져온 기준</strong>
                  <span>{importSummary.workspaceSummary}</span>
                </div>
                <button
                  className="solid-btn"
                  disabled={!canSubmitAnswers || submitPhase === "loading"}
                  onClick={() => void handleSubmitAnswers()}
                  type="button"
                >
                  {submitPhase === "loading" ? "AI 제안 생성 중..." : "답변 저장하고 AI 제안 보기"}
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
          <p className="eyebrow">AI Preview</p>
          <h1>답변과 현재 데이터를 바탕으로 첫 운영 루틴을 만들었습니다.</h1>
          <p>{onboardingStatus.experience?.summary ?? "바로 적용하거나, 제안 없이 시작할 수 있습니다."}</p>

          <div className="onboarding-answer-summary">
            {answerSummary.map((item) => (
              <div key={item.id} className="onboarding-answer-chip">
                <strong>{item.title}</strong>
                <span>{item.value}</span>
              </div>
            ))}
          </div>

          <div className="onboarding-overview-list">
            <div className="onboarding-overview-item">
              <strong>현재 기준 데이터</strong>
              <span>{importSummary.workspaceSummary}</span>
            </div>
            <div className="onboarding-overview-item">
              <strong>캘린더 마지막 확인</strong>
              <span>{formatDateTime(importSummary.lastCalendarSyncAt, onboardingTimeZone)}</span>
            </div>
            <div className="onboarding-overview-item">
              <strong>Tasks 마지막 확인</strong>
              <span>{formatDateTime(importSummary.lastTaskSyncAt, onboardingTimeZone)}</span>
            </div>
          </div>
        </section>

        <section className="onboarding-main">
          <article className="surface-card onboarding-card onboarding-preview-card">
            <div className="onboarding-stage-head">
              <div>
                <p className="eyebrow">First Suggestion</p>
                <h2>이 패턴들을 먼저 고정해 두고 AI가 이후 재조율을 시작합니다.</h2>
                <p className="section-header-note">
                  이미 있는 일정과 겹치지 않는 블록만 골라 제안했습니다. 필요하면 지금은 건너뛰고 나중에 다시 받을 수 있습니다.
                </p>
              </div>
              <span className="accent-pill">
                {previewItems.length > 0 ? `${previewItems.length} routines` : "explain only"}
              </span>
            </div>

            {previewItems.length > 0 ? (
              <div className="onboarding-preview-list">
                {previewItems.map((item) => (
                  <div key={`${item.title}-${item.days}-${item.startTime}`} className="onboarding-preview-item">
                    <div className="onboarding-preview-copy">
                      <strong>{item.title}</strong>
                      <span>
                        {item.days} · {formatClockValue(item.startTime)} - {formatClockValue(item.endTime)}
                      </span>
                    </div>
                    <div className="onboarding-preview-meta">
                      <span className="accent-pill subtle">{item.category}</span>
                      <p>{item.reason}</p>
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <section className="surface-card empty-state subtle">
                <strong>바로 추가할 새 루틴은 많지 않았습니다.</strong>
                <p>
                  현재는 기존 일정 흐름을 우선 유지하고, 이후 대시보드에서 상황 변화가 생기면 AI 제안을 이어서 받을 수 있습니다.
                </p>
              </section>
            )}

            <div className="onboarding-step-actions">
              <button
                className="ghost-btn"
                disabled={completionPhase === "loading"}
                onClick={() => void handleComplete(false)}
                type="button"
              >
                제안 없이 시작
              </button>
              <button
                className="solid-btn"
                disabled={completionPhase === "loading" || (!suggestionId && previewItems.length > 0)}
                onClick={() => void handleComplete(true)}
                type="button"
              >
                {completionPhase === "loading" ? "적용 중..." : "이 제안으로 시작하기"}
              </button>
            </div>
          </article>
        </section>
      </div>
    </div>
  );
}
