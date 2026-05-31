"use client";

import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";

import { useOnboardingBootstrap } from "@/hooks/use-onboarding-bootstrap";
import { useSessionBootstrap } from "@/hooks/use-session-bootstrap";
import { api } from "@/lib/api";
import { formatServiceCopy } from "@/lib/format";
import { OnboardingQuestion, OnboardingStatus } from "@/lib/types";
import { useAppStore } from "@/stores/app-store";

type AsyncPhase = "idle" | "loading" | "ready" | "error";

const QUESTION_GROUPS = [
  {
    id: "morning",
    title: "아침",
    description: "",
    questionIds: ["wakeTime", "workStartTime"],
  },
  {
    id: "evening",
    title: "저녁",
    description: "",
    questionIds: ["dinnerTime", "sleepTime"],
  },
  {
    id: "weekend",
    title: "주말",
    description: "",
    questionIds: ["weekendStyle"],
  },
  {
    id: "focus",
    title: "집중 시간",
    description: "",
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
  const displayName = onboardingStatus?.displayName || session?.displayName || "새 사용자";
  const onboardingTimeZone = onboardingStatus?.timezone || session?.timezone;
  const suggestionId = onboardingStatus?.experience?.suggestion?.id;

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
  const canSubmitAnswers = questions.length > 0 && questions.every((question) => Boolean(answers[question.id]));
  const answerReadyLabel = canSubmitAnswers
    ? `${answeredCount}/${questions.length} 답변 완료`
    : `${answeredCount}/${questions.length} 답변`;
  const primaryActionLabel = submitPhase === "loading" ? "오늘 화면 준비 중..." : "오늘 일정표 보기";
  const readinessStateCopy = canSubmitAnswers
    ? "오늘 일정표를 바로 열 수 있습니다."
    : "모두 고르면 오늘 일정표로 넘어갑니다.";

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
        title: "설정을 저장했습니다.",
        detail: "오늘 일정 화면으로 이어집니다.",
      });
    } catch (error) {
      setSubmitPhase("error");
      setSubmitMessage(
        error instanceof Error ? error.message : "처음 설정 답변 저장에 실패했습니다.",
      );
      showNotice({
        tone: "error",
        title: "생활 리듬 저장에 실패했습니다.",
        detail: error instanceof Error ? error.message : "잠시 후 다시 시도하면 됩니다.",
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
        title: "처음 설정을 마쳤습니다.",
        detail: response.message,
      });
      router.replace("/dashboard");
    } catch (error) {
      setCompletionPhase("error");
      showNotice({
        tone: "error",
        title: "처음 설정 완료 처리에 실패했습니다.",
        detail: error instanceof Error ? error.message : "잠시 후 다시 시도하면 됩니다.",
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
          <h1>처음 설정을 준비합니다.</h1>
          <p>필요한 질문을 준비합니다.</p>
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
          <p>{onboardingError ?? "잠시 후 다시 시도하면 됩니다."}</p>
          <button className="solid-btn" data-testid="status-retry-action" onClick={() => void refreshOnboarding()}>
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
            <p className="eyebrow">처음 설정</p>
            <h1>필요한 정보를 준비합니다.</h1>
            <div className="loading-dots" aria-hidden="true">
              <span />
              <span />
              <span />
            </div>
            {bootstrapMessage ? <p className="micro-copy">{bootstrapMessage}</p> : null}
            {bootstrapPhase === "error" ? (
              <div className="guest-actions">
                <button className="solid-btn" data-testid="status-retry-action" onClick={() => void handleBootstrapRetry()}>
                  다시 읽기
                </button>
                <button className="ghost-btn" data-testid="status-retry-action" onClick={() => void refreshOnboarding()}>
                  상태 새로고침
                </button>
              </div>
            ) : null}
          </section>
        </div>
      </div>
    );
  }

  if (isQuestionStage) {
    return (
      <div className={`status-screen onboarding-shell ${canSubmitAnswers ? "onboarding-shell-ready" : ""}`}>
        <div className="onboarding-panel onboarding-panel-wide">
          <section className="onboarding-sidebar onboarding-sidebar-compact onboarding-quickstart-rail">
            <div className="onboarding-sidebar-body">
              <p className="eyebrow">처음 설정</p>
              <h1>평소 시간을 몇 개만 고르세요.</h1>
              <p>오늘 일정표를 바로 볼 수 있습니다. 나중에 언제든 바꿀 수 있습니다.</p>
            </div>

            <div className="onboarding-sidebar-meta onboarding-readiness-card" aria-label="처음 설정 답변 상태">
              <div className="onboarding-readiness-head">
                <span>시작 준비</span>
                <strong>{answerReadyLabel}</strong>
              </div>
              <p>{readinessStateCopy}</p>
              <div className="onboarding-readiness-summary" aria-label="오늘 화면에 반영되는 항목">
                {QUESTION_GROUPS.map((group) => (
                  <span key={group.id}>{group.title}</span>
                ))}
              </div>
              <ul className="onboarding-helper-list">
                <li>정확하지 않아도 됩니다.</li>
                <li>나중에 바꿀 수 있습니다.</li>
              </ul>
            </div>
          </section>

          <section className="onboarding-main">
            <article className="surface-card onboarding-card onboarding-question-card">
              <div className="onboarding-stage-head">
                <div>
                  <p className="eyebrow">시간 선택</p>
                  <h2>대략 맞는 시간으로 충분합니다.</h2>
                </div>
                <span className="accent-pill onboarding-answer-count" data-testid="onboarding-answer-count">
                  {answerReadyLabel}
                </span>
              </div>

              <div className="onboarding-question-groups">
                {groupedQuestions.map((group) => (
                  <section key={group.id} className="onboarding-group-card">
                    <div className="onboarding-group-head">
                      <div>
                        <strong>{group.title}</strong>
                        {group.description ? <p>{group.description}</p> : null}
                      </div>
                      <span>
                        {group.questions.filter((question) => Boolean(answers[question.id])).length}/{group.questions.length}
                      </span>
                    </div>

                    {group.questions.map((question) => (
                      <section key={question.id} className="onboarding-question-block">
                        <div className="onboarding-question-head">
                          <strong>{question.title}</strong>
                          {question.description ? <p>{question.description}</p> : null}
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
                                <span className="onboarding-option-indicator" aria-hidden="true" />
                                <span className="onboarding-option-copy">
                                  <strong>{option.label}</strong>
                                  {option.helper ? <span data-testid="onboarding-option-helper">{option.helper}</span> : null}
                                </span>
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
                <button
                  className="solid-btn"
                  data-testid="onboarding-continue-button"
                  disabled={!canSubmitAnswers || submitPhase === "loading"}
                  onClick={() => void handleSubmitAnswers()}
                  type="button"
                >
                  {primaryActionLabel}
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
      <div className="onboarding-panel onboarding-panel-wide onboarding-panel-simple">
        <section className="onboarding-sidebar onboarding-sidebar-compact">
          <p className="eyebrow">처음 설정</p>
          <h1>오늘 일정표를 볼 준비가 끝났습니다.</h1>
          <p>선택한 시간으로 오늘 일정과 지금 할 일을 바로 확인할 수 있습니다.</p>
          <div className="onboarding-answer-summary">
            {answerSummary.map((item) => (
              <div key={item.id} className="onboarding-answer-chip">
                <strong>{formatServiceCopy(item.title)}</strong>
                <span>{item.value}</span>
              </div>
            ))}
          </div>
        </section>

        <section className="onboarding-main">
          <article className="surface-card onboarding-card onboarding-preview-card">
            <div className="onboarding-stage-head">
              <div>
                <p className="eyebrow">완료</p>
                <h2>오늘 일정표를 바로 열 수 있습니다.</h2>
                <p className="onboarding-card-intro">
                  나중에 다시 바꿀 수 있습니다.
                </p>
              </div>
            </div>

            <div className="onboarding-step-actions">
              <button
                className="ghost-btn"
                disabled={completionPhase === "loading"}
                onClick={() => setIsEditingAnswers(true)}
                type="button"
              >
                다시 수정
              </button>

              <div className="board-head-actions">
                <button
                  className="ghost-btn"
                  data-testid={!suggestionId ? "onboarding-complete-primary" : undefined}
                  disabled={completionPhase === "loading"}
                  onClick={() => void handleComplete(false)}
                  type="button"
                >
                  오늘 화면으로 이동
                </button>
                {suggestionId ? (
                  <button
                    className="solid-btn"
                    data-testid="onboarding-complete-primary"
                    disabled={completionPhase === "loading"}
                    onClick={() => void handleComplete(true)}
                    type="button"
                  >
                    {completionPhase === "loading" ? "오늘 화면 준비 중..." : "오늘 일정표 보기"}
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
