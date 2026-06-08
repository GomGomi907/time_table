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

const UNSAFE_USER_COPY_PATTERN =
  /providerMetadata|provider_metadata|payload|validationTrace|reasoningTrace|rawPrompt|commandBatch|requestKind|resolutionType/i;

const QUESTION_GROUPS = [
  {
    id: "morning",
    title: "아침",
    description: "일어나는 시간과 업무 시작 전 흐름을 잡습니다.",
    questionIds: ["wakeTime", "workStartTime"],
  },
  {
    id: "evening",
    title: "저녁",
    description: "저녁과 수면 보호 시간을 정합니다.",
    questionIds: ["dinnerTime", "sleepTime"],
  },
  {
    id: "weekend",
    title: "주말",
    description: "주말을 쉬는 쪽으로 둘지, 개인 작업 시간을 둘지 고릅니다.",
    questionIds: ["weekendStyle"],
  },
  {
    id: "focus",
    title: "집중 시간",
    description: "실행 모드가 권할 집중/휴식 리듬을 정합니다.",
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

function categoryCopy(category: string | undefined) {
  switch (category) {
    case "SLEEP":
      return "수면";
    case "TRANSIT":
      return "이동";
    case "LIFE":
      return "생활";
    case "WORK":
      return "업무";
    case "GROWTH":
      return "성장";
    default:
      return "일정";
  }
}

function timezoneCopy(timezone: string | undefined) {
  if (!timezone) {
    return "나중에 언제든 바꿀 수 있습니다.";
  }
  if (timezone === "Asia/Seoul") {
    return "서울 시간 기준으로 저장합니다.";
  }
  return "현재 시간대 기준으로 저장합니다.";
}

function previewTitleCopy(title: string | undefined, category: string | undefined) {
  if (!title || UNSAFE_USER_COPY_PATTERN.test(title)) {
    return `${categoryCopy(category)} 시간`;
  }
  return formatServiceCopy(title);
}

export function OnboardingView() {
  const router = useRouter();
  const { session, sessionPhase, refreshSession } = useSessionBootstrap();
  const {
    onboardingPhase,
    onboardingStatus,
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
  const [touchedAnswers, setTouchedAnswers] = useState<Record<string, boolean>>({});

  const questions = useMemo(() => onboardingStatus?.questions ?? [], [onboardingStatus?.questions]);
  const displayName = onboardingStatus?.displayName || session?.displayName || "새 사용자";
  const onboardingTimeZone = onboardingStatus?.timezone || session?.timezone;
  const suggestionId = onboardingStatus?.experience?.suggestion?.id;
  const onboardingPreviewItems = onboardingStatus?.experience?.previewItems ?? [];
  const visibleOnboardingPreviewItems = onboardingPreviewItems.slice(0, 3);
  const hiddenOnboardingPreviewCount = Math.max(onboardingPreviewItems.length - visibleOnboardingPreviewItems.length, 0);
  const canApplyOnboardingSuggestion = Boolean(suggestionId && onboardingPreviewItems.length > 0);
  const onboardingExperienceSummary = canApplyOnboardingSuggestion
    ? `추천 시간 ${onboardingPreviewItems.length}개를 준비했습니다.`
    : "일정표를 바로 열 수 있습니다.";

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
        setBootstrapMessage("필요한 정보를 준비했습니다.");
        setBootstrapPhase("ready");
      } catch {
        setBootstrapPhase("error");
        setBootstrapMessage("필요한 정보를 준비하지 못했습니다.");
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
  const touchedAnswerCount = Object.values(touchedAnswers).filter(Boolean).length;
  const answerReadyLabel = canSubmitAnswers
    ? touchedAnswerCount > 0
      ? `${touchedAnswerCount}개 조정됨`
      : "추천 기본값 준비됨"
    : `${answeredCount}/${questions.length} 준비`;
  const primaryActionLabel = submitPhase === "loading"
    ? "준비 중..."
    : touchedAnswerCount > 0
      ? "바꾼 시간으로 계속"
      : "기본값으로 계속";
  const readinessStateCopy = canSubmitAnswers
    ? touchedAnswerCount > 0
      ? "바꾼 시간으로 오늘 일정표를 준비합니다."
      : "추천 기본값을 그대로 쓰거나 필요한 것만 바꾸세요."
    : "모두 고르면 마지막 확인으로 넘어갑니다.";

  function updateAnswer(questionId: string, value: string) {
    setAnswers((current) => ({
      ...current,
      [questionId]: value,
    }));
    setTouchedAnswers((current) => ({
      ...current,
      [questionId]: true,
    }));
    setSubmitMessage(null);
    if (submitPhase === "error") {
      setSubmitPhase("idle");
    }
  }

  async function handleBootstrapRetry() {
    try {
      setBootstrapPhase("loading");
      const response = await api.bootstrapOnboarding();
      applyOnboardingStatus(response.status);
      setBootstrapMessage("필요한 정보를 준비했습니다.");
      setBootstrapPhase("ready");
    } catch {
      setBootstrapPhase("error");
      setBootstrapMessage("필요한 정보를 준비하지 못했습니다.");
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
      setSubmitMessage("오늘 일정표 준비를 마쳤습니다.");
      setSubmitPhase("ready");
      setIsEditingAnswers(false);
      setTouchedAnswers({});
      showNotice({
        tone: "success",
        title: "오늘 일정표를 준비했습니다.",
        detail: "마지막 확인으로 이어집니다.",
      });
    } catch {
      setSubmitPhase("error");
      setSubmitMessage(
        "오늘 일정표를 준비하지 못했습니다.",
      );
      showNotice({
        tone: "error",
        title: "오늘 일정표를 준비하지 못했습니다.",
        detail: "잠시 후 다시 시도하면 됩니다.",
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
        detail: "일정표를 엽니다.",
      });
      router.replace("/dashboard");
    } catch {
      setCompletionPhase("error");
      showNotice({
        tone: "error",
        title: "처음 설정 완료 처리에 실패했습니다.",
        detail: "잠시 후 다시 시도하면 됩니다.",
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
          <p>잠시 후 다시 시도하면 됩니다.</p>
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
                <button className="ghost-btn" data-testid="onboarding-refresh-status-action" onClick={() => void refreshOnboarding()}>
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
              <h1>{displayName}님, 평소 시간을 몇 개만 고르세요.</h1>
              <p>오늘 일정표를 바로 볼 수 있습니다. {timezoneCopy(onboardingTimeZone)}</p>
            </div>

            <div className="onboarding-sidebar-meta onboarding-readiness-card" aria-label="처음 설정 답변 상태">
              <div className="onboarding-readiness-head">
                <span>시작 준비</span>
                <strong>{answerReadyLabel}</strong>
              </div>
              <p>{readinessStateCopy}</p>
              <div className="onboarding-readiness-summary" aria-label="일정표에 반영되는 항목">
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
                                aria-pressed={selected}
                                onClick={() => updateAnswer(question.id, option.value)}
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
                  {canApplyOnboardingSuggestion
                    ? "추천 시간을 넣을지 고른 뒤 일정표를 엽니다."
                    : "나중에 다시 바꿀 수 있습니다."}
                </p>
              </div>
            </div>

            {onboardingExperienceSummary ? (
              <div className="onboarding-preview-summary" aria-label="추천 시간 요약">
                <span>추천 시간</span>
                <strong>{onboardingExperienceSummary}</strong>
              </div>
            ) : null}

            {visibleOnboardingPreviewItems.length ? (
              <ul className="suggestion-preview-list" aria-label="온보딩에서 넣을 수 있는 추천 시간">
                {visibleOnboardingPreviewItems.map((item, index) => (
                  <li className="suggestion-preview-item" key={`${item.title}-${item.startTime}-${index}`}>
                    <span>{categoryCopy(item.category)}</span>
                    <strong>{previewTitleCopy(item.title, item.category)}</strong>
                    <p>
                      {item.days} · {item.startTime} - {item.endTime}
                    </p>
                  </li>
                ))}
                {hiddenOnboardingPreviewCount ? (
                  <li className="suggestion-preview-more">외 {hiddenOnboardingPreviewCount}개 추천 시간</li>
                ) : null}
              </ul>
            ) : null}

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
                  className={canApplyOnboardingSuggestion ? "ghost-btn" : "solid-btn"}
                  data-testid={!canApplyOnboardingSuggestion ? "onboarding-complete-primary" : undefined}
                  disabled={completionPhase === "loading"}
                  onClick={() => void handleComplete(false)}
                  type="button"
                >
                  {canApplyOnboardingSuggestion ? "건너뛰고 일정표 보기" : "일정표 보기"}
                </button>
                {canApplyOnboardingSuggestion ? (
                  <button
                    className="solid-btn"
                    data-testid="onboarding-complete-primary"
                    disabled={completionPhase === "loading"}
                    onClick={() => void handleComplete(true)}
                    type="button"
                  >
                    {completionPhase === "loading" ? "추천 시간 넣는 중..." : "추천 시간 넣고 일정표 보기"}
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
