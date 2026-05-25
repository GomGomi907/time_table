export interface OnboardingDayAnswerSnapshot {
  id: string;
  title: string;
  value: string;
}

export interface OnboardingDayHandoff {
  createdAt: string;
  appliedSuggestion: boolean;
  suggestionSummary: string | null;
  suggestionExplanation: string | null;
  answers: OnboardingDayAnswerSnapshot[];
  dashboardDismissed: boolean;
  scheduleDismissed: boolean;
}

interface OnboardingDayHandoffWrite {
  appliedSuggestion: boolean;
  suggestionSummary: string | null;
  suggestionExplanation: string | null;
  answers: OnboardingDayAnswerSnapshot[];
}

const ONBOARDING_DAY_MAX_AGE_MS = 24 * 60 * 60 * 1000;

function onboardingDayKey(userId: string) {
  return `tt-onboarding-day:${userId}`;
}

function isExpired(createdAt: string) {
  const timestamp = Date.parse(createdAt);
  if (Number.isNaN(timestamp)) {
    return true;
  }

  return Date.now() - timestamp > ONBOARDING_DAY_MAX_AGE_MS;
}

function persist(userId: string, handoff: OnboardingDayHandoff | null) {
  if (typeof window === "undefined") {
    return;
  }

  const key = onboardingDayKey(userId);
  if (!handoff || (handoff.dashboardDismissed && handoff.scheduleDismissed)) {
    window.localStorage.removeItem(key);
    return;
  }

  window.localStorage.setItem(key, JSON.stringify(handoff));
}

export function readOnboardingDayHandoff(userId: string | undefined) {
  if (typeof window === "undefined" || !userId) {
    return null;
  }

  const raw = window.localStorage.getItem(onboardingDayKey(userId));
  if (!raw) {
    return null;
  }

  try {
    const parsed = JSON.parse(raw) as Partial<OnboardingDayHandoff>;
    if (!parsed.createdAt || isExpired(parsed.createdAt)) {
      window.localStorage.removeItem(onboardingDayKey(userId));
      return null;
    }

    return {
      createdAt: parsed.createdAt,
      appliedSuggestion: Boolean(parsed.appliedSuggestion),
      suggestionSummary:
        typeof parsed.suggestionSummary === "string" ? parsed.suggestionSummary : null,
      suggestionExplanation:
        typeof parsed.suggestionExplanation === "string" ? parsed.suggestionExplanation : null,
      answers: Array.isArray(parsed.answers)
        ? parsed.answers.filter(
            (item): item is OnboardingDayAnswerSnapshot =>
              Boolean(
                item &&
                  typeof item.id === "string" &&
                  typeof item.title === "string" &&
                  typeof item.value === "string",
              ),
          )
        : [],
      dashboardDismissed: Boolean(parsed.dashboardDismissed),
      scheduleDismissed: Boolean(parsed.scheduleDismissed),
    } satisfies OnboardingDayHandoff;
  } catch {
    window.localStorage.removeItem(onboardingDayKey(userId));
    return null;
  }
}

export function writeOnboardingDayHandoff(userId: string, handoff: OnboardingDayHandoffWrite) {
  persist(userId, {
    createdAt: new Date().toISOString(),
    appliedSuggestion: handoff.appliedSuggestion,
    suggestionSummary: handoff.suggestionSummary,
    suggestionExplanation: handoff.suggestionExplanation,
    answers: handoff.answers,
    dashboardDismissed: false,
    scheduleDismissed: false,
  });
}

export function dismissOnboardingDayHandoffHint(
  userId: string | undefined,
  target: "dashboard" | "schedule",
) {
  if (!userId) {
    return;
  }

  const current = readOnboardingDayHandoff(userId);
  if (!current) {
    return;
  }

  const next: OnboardingDayHandoff = {
    ...current,
    dashboardDismissed:
      target === "dashboard" ? true : current.dashboardDismissed,
    scheduleDismissed:
      target === "schedule" ? true : current.scheduleDismissed,
  };

  persist(userId, next);
}
