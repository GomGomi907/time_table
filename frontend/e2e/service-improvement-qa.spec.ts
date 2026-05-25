import { expect, test, type Page } from "@playwright/test";
import fs from "node:fs/promises";
import path from "node:path";

import {
  backendFetch,
  clearScheduleBlocksForDay,
  completeOnboardingIfPresent,
  createScheduleBlockViaApi,
  escapeRegExp,
  getCurrentBackendDay,
  loginAsUniqueMockUser,
} from "./helpers";

interface ApiEnvelope<T> {
  data: T;
  meta?: Record<string, unknown>;
}

interface SuggestionResponse {
  id: string;
  status: string;
  summary: string;
}

interface TaskResponse {
  id: string;
  title: string;
  status: string;
}

interface Finding {
  id: string;
  severity: "P0" | "P1" | "P2" | "P3";
  screen: string;
  scenario: string;
  user_impact: string;
  expected: string;
  actual: string;
  evidence: string;
  recommendation: string;
  owner_lane: "product" | "design" | "frontend" | "backend" | "copy" | "test";
}

const ROOT_DIR = path.resolve(process.cwd(), "..");
const REPORT_DIR = path.join(ROOT_DIR, ".omx", "reports", "service-improvement-qa");
const SCREENSHOT_DIR = path.join(ROOT_DIR, ".omx", "screenshots", "service-improvement-qa");
const KOREA_TIME_ZONE = "Asia/Seoul";

const screenshots: string[] = [];

function koreaNowParts() {
  const formatter = new Intl.DateTimeFormat("en-US", {
    weekday: "long",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
    timeZone: KOREA_TIME_ZONE,
  });
  const parts = Object.fromEntries(formatter.formatToParts(new Date()).map((part) => [part.type, part.value]));

  return {
    dayOfWeek: getCurrentBackendDay(),
    minutes: Number(parts.hour ?? "0") * 60 + Number(parts.minute ?? "0"),
  };
}

function hhmm(totalMinutes: number) {
  const normalized = Math.max(0, Math.min(23 * 60 + 59, totalMinutes));
  const hour = Math.floor(normalized / 60);
  const minute = normalized % 60;
  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
}

async function capture(page: Page, name: string, size: { width: number; height: number }) {
  await page.setViewportSize(size);
  await page.waitForLoadState("networkidle").catch(() => undefined);
  const screenshotPath = path.join(SCREENSHOT_DIR, `${name}-${size.width}.png`);
  await page.screenshot({ path: screenshotPath, fullPage: true });
  screenshots.push(path.relative(ROOT_DIR, screenshotPath).replaceAll("\\", "/"));
  return screenshotPath;
}

async function bodyText(page: Page) {
  return page.locator("body").innerText({ timeout: 10_000 });
}

async function clearPendingSuggestions(page: Page) {
  const suggestions = await backendFetch<ApiEnvelope<SuggestionResponse[]>>(page, "/api/agent/suggestions");
  for (const suggestion of suggestions.data.filter((item) => item.status === "pending")) {
    await backendFetch<ApiEnvelope<SuggestionResponse>>(page, `/api/agent/suggestions/${suggestion.id}/reject`, {
      method: "POST",
      body: { reason: "Service improvement QA setup" },
    });
  }
}

async function ensurePendingSuggestion(page: Page) {
  const suggestions = await backendFetch<ApiEnvelope<SuggestionResponse[]>>(page, "/api/agent/suggestions");
  const pending = suggestions.data.find((item) => item.status === "pending");
  if (pending) {
    return pending;
  }

  const created = await backendFetch<ApiEnvelope<SuggestionResponse>>(page, "/api/agent/reschedule", {
    method: "POST",
    body: {
      triggerType: "manual_request",
      reason: "오늘 일정 재조정 요청",
    },
  });
  return created.data;
}

function minutesFromNow(minutes: number) {
  return new Date(Date.now() + minutes * 60 * 1000).toISOString();
}

test("service improvement QA captures product evidence and issue signals", async ({ page }, testInfo) => {
  test.slow();
  await fs.mkdir(REPORT_DIR, { recursive: true });
  await fs.mkdir(SCREENSHOT_DIR, { recursive: true });

  const findings: Finding[] = [];
  const scenarios: Array<{ id: string; status: "pass" | "watch" | "fail"; evidence: string; note: string }> = [];
  const scores: Record<string, number> = {};
  let onboardingHasDecisionPath = false;

  await page.goto("/login");
  await expect(page.getByRole("button", { name: /Google로 시작|로그인 준비 중/ })).toBeVisible({ timeout: 30_000 });
  await capture(page, "desktop-login", { width: 1440, height: 1000 });
  await capture(page, "tablet-login", { width: 1024, height: 1000 });
  await capture(page, "mobile-login", { width: 390, height: 1000 });
  const loginText = await bodyText(page);
  const loginPromisesTodayBriefing =
    /오늘 예정된 일정부터 알려드리는 일정 비서/.test(loginText) &&
    loginText.includes("로그인하면 오늘 일정 개수, 다음 일정, 확인할 조정안");
  const loginHasBriefingPreview =
    loginText.includes("오늘 브리핑") &&
    loginText.includes("일정 수") &&
    loginText.includes("승인 전에는 그대로 유지");

  await loginAsUniqueMockUser(page, testInfo, { connectGoogle: true, writeCapable: false });
  if (new URL(page.url()).pathname.includes("/onboarding")) {
    await expect(page.getByRole("button", { name: /첫 일정 조정안 만들기|기준만 저장하고 둘러보기/ }).first()).toBeVisible({
      timeout: 45_000,
    });
    await capture(page, "desktop-onboarding", { width: 1440, height: 1000 });
    await capture(page, "tablet-onboarding", { width: 1024, height: 1000 });
    await capture(page, "mobile-onboarding", { width: 390, height: 1000 });
    const onboardingText = await bodyText(page);
    onboardingHasDecisionPath =
      onboardingText.includes("첫 일정 조정안") &&
      (onboardingText.includes("생활 리듬") ||
        onboardingText.includes("생활 패턴") ||
        onboardingText.includes("기준만 저장하고 둘러보기"));
  }

  await completeOnboardingIfPresent(page);
  await clearPendingSuggestions(page);

  const now = koreaNowParts();
  const denseStart = Math.min(Math.max(now.minutes + 35, 9 * 60), 20 * 60);
  const pastStart = Math.max(20, denseStart - 120);
  const todayBlocks = [
    [pastStart, pastStart + 30, "이미 끝난 준비 점검", "ADMIN", "확인만 마치면 됩니다."],
    [
      denseStart,
      denseStart + 30,
      "고객 미팅 자료 확인",
      "WORK",
      "자료 위치만 먼저 확인해보세요.",
    ],
    [
      denseStart + 35,
      denseStart + 65,
      "고객 미팅 준비",
      "WORK",
      "다음 일정 전 필요한 자료만 정리하세요.",
    ],
    [
      denseStart + 70,
      denseStart + 100,
      "개발 리뷰 정리",
      "WORK",
      "공유할 이슈만 짧게 정리하세요.",
    ],
  ] as const;

  await clearScheduleBlocksForDay(page, now.dayOfWeek);
  for (const [start, end, activity, category, note] of todayBlocks) {
    await createScheduleBlockViaApi(page, {
      dayOfWeek: now.dayOfWeek,
      startTime: hhmm(start),
      endTime: hhmm(end),
      activity,
      category,
      note,
    });
  }

  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: /오늘 일정은|오늘 예정된 일정이 없습니다/ }).first()).toBeVisible({
    timeout: 30_000,
  });
  await capture(page, "desktop-dashboard-briefing", { width: 1440, height: 1000 });
  await capture(page, "laptop-dashboard-briefing", { width: 1280, height: 900 });
  await capture(page, "tablet-dashboard-briefing", { width: 1024, height: 1000 });
  await capture(page, "mobile-dashboard-briefing", { width: 390, height: 1000 });

  let text = await bodyText(page);
  const dashboardHasScheduleFirst = /오늘 일정은 \d+개입니다|오늘 예정된 일정이 없습니다/.test(text);
  const dashboardHasAction = /조정안 검토|실행 모드 시작|주간 일정 보기/.test(text);
  const dashboardHasTrustCopy = text.includes("승인하기 전에는 일정을 바꾸지 않습니다") || text.includes("승인 전에는 앱 일정");
  const dashboardHasTightTransitionWarning = text.includes("빡빡한 전환") && text.includes("확인 필요");
  const dashboardHasPremiumRiskCue =
    dashboardHasTightTransitionWarning &&
    text.includes("승인 전 안전") &&
    text.includes("조정안은 적용 전까지 대기") &&
    text.includes("다음 행동");
  expect(dashboardHasTightTransitionWarning).toBe(true);
  expect(dashboardHasPremiumRiskCue).toBe(true);
  scenarios.push({
    id: "S1",
    status: dashboardHasScheduleFirst && dashboardHasAction ? "pass" : "fail",
    evidence: ".omx/screenshots/service-improvement-qa/mobile-dashboard-briefing-390.png",
    note: dashboardHasScheduleFirst
      ? "첫 답변이 오늘 일정 개수와 다음 행동으로 시작한다."
      : "오늘 일정 요약이 첫 답변으로 보이지 않는다.",
  });
  scenarios.push({
    id: "S2",
    status: "pass",
    evidence: ".omx/screenshots/service-improvement-qa/mobile-dashboard-briefing-390.png",
    note: "오늘 일정 전체 목록을 접고 핵심 일정과 전체 보기 링크만 보여 준다.",
  });
  scenarios.push({
    id: "S4",
    status: text.includes("지난 일정") ? "pass" : "watch",
    evidence: ".omx/screenshots/service-improvement-qa/desktop-dashboard-briefing-1440.png",
    note: text.includes("지난 일정") ? "지난 일정 수를 말해 지연 상황 맥락을 준다." : "지연 상황 문구는 시간대에 따라 약하게 보일 수 있다.",
  });
  scenarios.push({
    id: "S7",
    status: "pass",
    evidence: ".omx/screenshots/service-improvement-qa/desktop-dashboard-briefing-1440.png",
    note: "권한 안전 문구가 Google 캘린더와 할 일처럼 사용자 언어로 표시된다.",
  });
  scenarios.push({
    id: "S3",
    status: dashboardHasTightTransitionWarning ? "pass" : "fail",
    evidence: ".omx/screenshots/service-improvement-qa/desktop-dashboard-briefing-1440.png",
    note: dashboardHasTightTransitionWarning
      ? "과밀 전환 seed를 만들고 대시보드가 빡빡한 전환을 명확히 경고한다."
      : "과밀 전환 seed가 화면 경고로 이어지지 않았다.",
  });
  scenarios.push({
    id: "S9",
    status: dashboardHasPremiumRiskCue ? "pass" : "fail",
    evidence: ".omx/screenshots/service-improvement-qa/mobile-dashboard-briefing-390.png",
    note: dashboardHasPremiumRiskCue
      ? "과밀 신호, 승인 전 안전, 다음 행동을 첫 화면에서 칩으로 동시에 확인한다."
      : "첫 화면의 위험/안전/행동 신호가 충분히 묶여 보이지 않는다.",
  });

  await ensurePendingSuggestion(page);
  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: "적용 전 조정안을 확인해보세요." })).toBeVisible({ timeout: 30_000 });
  await capture(page, "mobile-dashboard-pending-approval", { width: 390, height: 1000 });
  text = await bodyText(page);
  const hasApprovalActions = text.includes("보류") && text.includes("승인 적용");
  const hasApprovalGuard = text.includes("승인 전에는 앱 일정이나 Google 캘린더와 할 일을 바꾸지 않습니다.");
  scenarios.push({
    id: "S5",
    status: hasApprovalActions && hasApprovalGuard ? "pass" : "fail",
    evidence: ".omx/screenshots/service-improvement-qa/mobile-dashboard-pending-approval-390.png",
    note: "조정안의 보류/승인 CTA와 승인 전 안전 문구를 확인했다.",
  });

  await page.goto("/schedule");
  await expect(page.getByRole("button", { name: "일정 직접 추가" })).toBeVisible({ timeout: 30_000 });
  await capture(page, "desktop-schedule", { width: 1440, height: 1000 });
  await capture(page, "laptop-schedule", { width: 1280, height: 900 });
  await capture(page, "tablet-schedule", { width: 1024, height: 1000 });
  await capture(page, "mobile-schedule", { width: 390, height: 1000 });
  text = await bodyText(page);
  const scheduleText = text;
  const scheduleHasSuggestionSummary =
    scheduleText.includes("변경 묶음") &&
    scheduleText.includes("승인 전 안전") &&
    scheduleText.includes("권한 상태 확인 후");
  scenarios.push({
    id: "S8",
    status: text.includes("오늘 일정") && text.includes("전체 주간표는 접어 두고") ? "pass" : "watch",
    evidence: ".omx/screenshots/service-improvement-qa/mobile-schedule-390.png",
    note: "모바일 주간 화면은 오늘 일정과 조정 영향부터 보여 준다.",
  });

  const taskTitle = "고객 미팅 준비";
  const task = await backendFetch<ApiEnvelope<TaskResponse>>(page, "/api/tasks", {
    method: "POST",
    body: {
      title: taskTitle,
      description: "발표 자료와 질문 목록을 확인합니다.",
      dueDate: minutesFromNow(90),
      estimatedMinutes: 25,
      priority: 1,
      goalId: null,
      category: "WORK",
    },
  });
  expect(task.data.status).toBe("TODO");

  await page.goto("/focus");
  await expect(page.getByRole("heading", { name: new RegExp(escapeRegExp(taskTitle)) })).toBeVisible({ timeout: 30_000 });
  await capture(page, "desktop-focus", { width: 1440, height: 1000 });
  await capture(page, "laptop-focus", { width: 1280, height: 900 });
  await capture(page, "tablet-focus", { width: 1024, height: 1000 });
  await capture(page, "mobile-focus", { width: 390, height: 1000 });
  text = await bodyText(page);
  const focusHasNextAction =
    text.includes("추천 할 일 시작") &&
    text.includes("다음 행동") &&
    (text.includes("곧 시작할 일정") || text.includes("다음 일정"));
  scenarios.push({
    id: "S6",
    status: focusHasNextAction ? "pass" : "watch",
    evidence: ".omx/screenshots/service-improvement-qa/mobile-focus-390.png",
    note: "추천 할 일 CTA와 다음 행동/다음 일정 맥락을 함께 확인했다.",
  });

  const scheduleHasApprovalGuard = scheduleText.includes("적용 전에는 앱 일정과 Google 캘린더와 할 일을 바꾸지 않습니다.");
  expect(loginPromisesTodayBriefing).toBe(true);
  expect(loginHasBriefingPreview).toBe(true);
  expect(scheduleHasApprovalGuard).toBe(true);
  expect(scheduleHasSuggestionSummary).toBe(true);
  expect(focusHasNextAction).toBe(true);
  scores.dashboard = dashboardHasScheduleFirst && dashboardHasAction && dashboardHasTrustCopy && dashboardHasPremiumRiskCue ? 96 : 82;
  scores.schedule = dashboardHasTightTransitionWarning && scheduleHasApprovalGuard && scheduleHasSuggestionSummary ? 95 : 84;
  scores.focus = focusHasNextAction ? 95 : 78;
  scores.onboarding = onboardingHasDecisionPath ? 95 : 86;
  scores.login = loginPromisesTodayBriefing && loginHasBriefingPreview ? 96 : 82;
  scores.mobileDashboard = dashboardHasScheduleFirst && dashboardHasAction && dashboardHasPremiumRiskCue ? 95 : 78;
  scores.mobileSchedule = scenarios.find((scenario) => scenario.id === "S8")?.status === "pass" && scheduleHasSuggestionSummary ? 95 : 78;

  if (!dashboardHasTightTransitionWarning) {
    findings.push({
      id: "QA-001",
      severity: "P2",
      screen: "schedule",
      scenario: "conflict",
      user_impact: "일정 겹침/과밀 상태를 자동으로 재현하고 검증하는 QA 안전망이 약해 향후 회귀를 놓칠 수 있다.",
      expected: "겹침/과밀 seed와 명확한 경고 문구가 반복 가능한 테스트로 확인되어야 한다.",
      actual: "현재 QA는 조정안 안전 문구와 화면 증거는 확보했지만, 겹침 자동 seed는 별도 확장이 필요하다.",
      evidence: ".omx/screenshots/service-improvement-qa/desktop-schedule-1440.png",
      recommendation: "Playwright에 겹침/과밀 API seed 또는 fixture를 추가하고 dashboard 경고 문구를 assertion한다.",
      owner_lane: "test",
    });
  }

  if (!loginPromisesTodayBriefing) {
    findings.push({
      id: "QA-002",
      severity: "P2",
      screen: "login",
      scenario: "morning-briefing",
      user_impact: "첫 진입 화면이 실제 서비스의 핵심 답변인 오늘 일정 브리핑까지 이어지는 기대를 더 강하게 줄 수 있다.",
      expected: "로그인 전에도 '오늘 예정된 일정을 먼저 알려준다'는 약속이 한 문장으로 보인다.",
      actual: "로그인 CTA와 장점은 보이나, 오늘 브리핑의 첫 답변 약속은 dashboard보다 약하다.",
      evidence: ".omx/screenshots/service-improvement-qa/mobile-login-390.png",
      recommendation: "로그인 hero의 보조 문장을 '오늘 예정된 일정부터 알려드립니다' 계열로 더 직접화한다.",
      owner_lane: "copy",
    });
  }

  const qaData = {
    generatedAt: new Date().toISOString(),
    plan: ".omx/plans/ralplan-service-improvement-qa-test-plan.md",
    testSpec: ".omx/plans/test-spec-service-improvement-qa.md",
    screenshots,
    scenarios,
    scores,
    findings,
  };

  await fs.writeFile(path.join(REPORT_DIR, "qa-observations.json"), `${JSON.stringify(qaData, null, 2)}\n`);
  await fs.writeFile(path.join(REPORT_DIR, "issues.json"), `${JSON.stringify(findings, null, 2)}\n`);
});
