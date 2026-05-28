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
    title: "평일 시작",
    questionIds: ["wakeTime", "workStartTime"],
  },
  {
    id: "evening",
    title: "저녁과 마감",
    questionIds: ["dinnerTime", "sleepTime"],
  },
  {
    id: "weekend",
    title: "주말 한 칸의 성향",
    questionIds: ["weekendStyle"],
  },
  {
    id: "focus",
    title: "집중 실행",
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
        title: "처음 설정을 마쳤습니다.",
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
          <h1>처음 설정을 준비하고 있습니다.</h1>
          <p>필요한 질문을 준비하고 있습니다.</p>
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
            <p className="eyebrow">처음 설정</p>
            <h1>필요한 정보를 준비하고 있습니다.</h1>
            <div className="loading-dots" aria-hidden="true">
              <span />
              <span />
              <span />
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
        </div>
      </div>
    );
  }

  if (isQuestionStage) {
    return (
      <div className="status-screen onboarding-shell">
        <div className="onboarding-panel onboarding-panel-wide">
          <section className="onboarding-sidebar onboarding-sidebar-compact">
            <p className="eyebrow">처음 설정</p>
            <h1>시작에 필요한 리듬만 알려 주세요.</h1>
          </section>

          <section className="onboarding-main">
            <article className="surface-card onboarding-card onboarding-question-card">
              <div className="onboarding-stage-head">
                <div>
                  <p className="eyebrow">생활 리듬</p>
                  <h2>시작에 필요한 것만 확인합니다.</h2>
                </div>
                <span className="accent-pill">
                  {answeredCount} / {questions.length} 답변
                </span>
              </div>

              <div className="onboarding-question-groups">
                {groupedQuestions.map((group) => (
                  <section key={group.id} className="onboarding-group-card">
                    <div className="onboarding-group-head">
                      <strong>{group.title}</strong>
                    </div>

                    {group.questions.map((question) => (
                      <section key={question.id} className="onboarding-question-block">
                        <div className="onboarding-question-head">
                          <strong>{question.title}</strong>
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
                  disabled={!canSubmitAnswers || submitPhase === "loading"}
                  onClick={() => void handleSubmitAnswers()}
                  type="button"
                >
                  {submitPhase === "loading" ? "저장 중..." : "저장하고 계속"}
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
          <h1>설정이 끝났습니다.</h1>
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
                <h2>오늘 화면으로 시작합니다.</h2>
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
                  disabled={completionPhase === "loading"}
                  onClick={() => void handleComplete(false)}
                  type="button"
                >
                  둘러보기
                </button>
                {suggestionId ? (
                  <button
                    className="solid-btn"
                    disabled={completionPhase === "loading"}
                    onClick={() => void handleComplete(true)}
                    type="button"
                  >
                    {completionPhase === "loading" ? "적용 중..." : "적용하고 시작"}
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
