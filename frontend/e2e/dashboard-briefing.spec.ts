import { expect, test, type Page } from "@playwright/test";

import {
  backendFetch,
  clearScheduleBlocksForDay,
  completeOnboardingIfPresent,
  createScheduleBlockViaApi,
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
  executable: boolean;
}

const BANNED_USER_COPY =
  /Google 연결|Google 계정 연결됨|Google 읽기|Google 반영 대기|마지막 동기화|연결 상태 확인|근거|기준으로 재배치|AI 비서 메모|예상 영향|확인한 내용|권한 상태|조정안 핵심 요약|추천 집중|실제 집중 상태|접어/;
const BANNED_AI_METADATA =
  /confidence|stage|matchEvidence|validationTrace|repairAttempt|chainOfThought|reasoning|reason|missingFields|ambiguousFields|INTERNAL_REASON_SHOULD_NOT_RENDER/i;

async function clearPendingSuggestions(page: Page) {
  const suggestions = await backendFetch<ApiEnvelope<SuggestionResponse[]>>(page, "/api/agent/suggestions");
  for (const suggestion of suggestions.data.filter((item) => item.status === "pending")) {
    await backendFetch<ApiEnvelope<SuggestionResponse>>(
      page,
      `/api/agent/suggestions/${suggestion.id}/reject`,
      {
        method: "POST",
        body: { reason: "Dashboard briefing e2e setup" },
      },
    );
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
      reason: `Dashboard briefing pending approval e2e ${Date.now()}`,
    },
  });
  expect(created.data.status).toBe("pending");
  return created.data;
}

async function expectVerticalOrder(page: Page, selectors: string[]) {
  const boxes = [];
  for (const selector of selectors) {
    const locator = page.locator(selector).first();
    await expect(locator).toBeVisible({ timeout: 30_000 });
    boxes.push(await locator.boundingBox());
  }

  for (let index = 0; index < boxes.length - 1; index += 1) {
    expect(boxes[index]?.y ?? Number.POSITIVE_INFINITY).toBeLessThan(
      boxes[index + 1]?.y ?? Number.NEGATIVE_INFINITY,
    );
  }
}

async function expectDashboardContentWidthMatchesHeader(page: Page) {
  const headerBox = await page.locator(".dashboard-top-bar").boundingBox();
  const contentBox = await page.locator(".today-briefing-shell").boundingBox();

  if (!headerBox || !contentBox) {
    throw new Error("Dashboard header and content boxes must be visible for width alignment check.");
  }

  expect(Math.abs(headerBox.x - contentBox.x)).toBeLessThanOrEqual(1);
  expect(Math.abs(headerBox.width - contentBox.width)).toBeLessThanOrEqual(2);
}

async function expectPendingSuggestionAction(page: Page, suggestion: Pick<SuggestionResponse, "executable">) {
  await expect(page.getByRole("button", { name: "보류" })).toBeVisible();

  if (suggestion.executable) {
    await expect(page.getByRole("button", { name: "적용" })).toBeEnabled();
    return;
  }

  await expect(page.getByRole("button", { name: /적용할 변경 없음|다시 요청 필요/ })).toBeDisabled();
}

const WEEK = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];

function buildNonExecutableSuggestion(
  resolutionType: "clarification_required" | "provider_unavailable",
  text: string,
) {
  const payload =
    resolutionType === "clarification_required"
      ? {
          resolutionType,
          clarificationQuestion: text,
          missingFields: ["date"],
          ambiguousFields: ["target"],
          reason: "INTERNAL_REASON_SHOULD_NOT_RENDER",
        }
      : {
          resolutionType,
          message: text,
        };

  return {
    id: `mock-${resolutionType}`,
    triggerType: "manual_request",
    status: "pending",
    statusLabel: "대기",
    statusDetail: "사용자 확인 필요",
    summary: resolutionType === "clarification_required" ? "확인이 필요합니다." : "요청을 처리하지 못했습니다.",
    reason: "INTERNAL_REASON_SHOULD_NOT_RENDER",
    explanation: text,
    commandBatch: {
      summary: "안내",
      explanation: text,
      commands: [
        {
          actionType: "explain_only",
          targetType: "schedule",
          targetId: null,
          payload,
          reason: "INTERNAL_REASON_SHOULD_NOT_RENDER",
          executable: false,
        },
      ],
    },
    previewItems: [],
    executableCommandCount: 0,
    executable: false,
    executionSummary: null,
    createdAt: new Date().toISOString(),
    appliedAt: null,
    rejectedAt: null,
    revertedAt: null,
  };
}

async function mockDashboardSummary(page: Page, suggestion: ReturnType<typeof buildNonExecutableSuggestion>) {
  await page.route("**/api/dashboard/summary", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        success: true,
        data: {
          week: {
            week: WEEK.map((dayOfWeek) => ({
              dayOfWeek,
              blocks: [],
            })),
          },
          goals: [],
          focus: {
            focusState: "IDLE",
            currentItem: null,
            nextItem: null,
            scheduleContext: null,
            preferenceContext: null,
            recommendedTasks: [],
            activeSuggestion: null,
            remainingMinutes: null,
          },
          sync: null,
          suggestions: [suggestion],
          metrics: {
            averageGoalProgress: 0,
            weeklyShapeScore: 0,
            scheduleBlockCount: 0,
            growthBlockCount: 0,
            topGoal: null,
          },
        },
        meta: {},
      }),
    });
  });
  await page.route(`**/api/agent/suggestions/${suggestion.id}/reject`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ success: true, data: { ...suggestion, status: "rejected" }, meta: {} }),
    });
  });
}


test("dashboard answers today's schedule before operational details", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo, { connectGoogle: true, writeCapable: false });
  await completeOnboardingIfPresent(page);

  const dayOfWeek = getCurrentBackendDay();
  await clearScheduleBlocksForDay(page, dayOfWeek);
  const blocks = [
    ["08:10", "08:40", "아침 계획 정리", "ADMIN"],
    ["09:10", "10:20", "제품 리뷰 회의", "WORK"],
    ["11:00", "11:50", "개발 집중 블록", "WORK"],
    ["13:20", "14:10", "오늘 할 일 정리", "ADMIN"],
    ["16:00", "17:00", "성장 과제", "GROWTH"],
    ["19:10", "20:00", "저녁 운동", "HEALTH"],
  ] as const;

  for (const [startTime, endTime, activity, category] of blocks) {
    await createScheduleBlockViaApi(page, {
      dayOfWeek,
      startTime,
      endTime,
      activity,
      category,
      note: "Dashboard hierarchy e2e seed",
    });
  }

  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: /오늘 일정은/ }).first()).toBeVisible({
    timeout: 30_000,
  });
  await expectDashboardContentWidthMatchesHeader(page);
  await expect(page.getByRole("heading", { name: "오늘 일정 핵심", exact: true })).toBeVisible();
  const scheduleCardText = await page.locator(".today-schedule-card").innerText();
  expect(scheduleCardText).toMatch(/아침 계획 정리|제품 리뷰 회의|개발 집중 블록|오늘 할 일 정리|성장 과제|저녁 운동/);
  await expect(page.locator(".today-schedule-card").getByText(/나머지 \d+개 일정/)).toBeVisible();
  await expect(page.getByText(/승인 전에는|승인할 조정안은 없습니다|승인 전 안전/).first()).toHaveCount(0);
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
});

test("dashboard shows clarification as a question instead of an approval task", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo, { connectGoogle: true, writeCapable: false });
  await completeOnboardingIfPresent(page);
  await mockDashboardSummary(
    page,
    buildNonExecutableSuggestion("clarification_required", "어느 날짜로 옮길까요?"),
  );

  await page.goto("/dashboard");

  await expect(page.getByRole("heading", { name: "확인이 필요합니다." })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText("어느 날짜로 옮길까요?")).toBeVisible();
  await expect(page.getByText("일정 정리 입력에 답변을 적어 다시 보내세요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "보류" })).toBeEnabled();
  await expect(page.getByRole("button", { name: "적용할 변경 없음" })).toBeDisabled();
  await expect(page.locator("body")).not.toContainText(BANNED_AI_METADATA);

  await page.getByRole("button", { name: "보류" }).click();
  await expect(page.getByRole("status")).toContainText("변경을 보류했습니다.", { timeout: 30_000 });
});

test("dashboard shows provider-unavailable as retry guidance without AI internals", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo, { connectGoogle: true, writeCapable: false });
  await completeOnboardingIfPresent(page);
  await mockDashboardSummary(
    page,
    buildNonExecutableSuggestion("provider_unavailable", "잠시 후 다시 시도해 주세요."),
  );

  await page.goto("/dashboard");

  await expect(page.getByRole("heading", { name: "지금은 적용할 수 없습니다." })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByText("잠시 후 다시 시도해 주세요.")).toBeVisible();
  await expect(page.getByText("잠시 후 다시 시도하세요.")).toBeVisible();
  await expect(page.getByRole("button", { name: "보류" })).toBeEnabled();
  await expect(page.getByRole("button", { name: "다시 요청 필요" })).toBeDisabled();
  await expect(page.locator("body")).not.toContainText(BANNED_AI_METADATA);
});

test("stacked today view keeps today's schedule first when no approval is pending", async ({
  page,
}, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo, { connectGoogle: true, writeCapable: false });
  await completeOnboardingIfPresent(page);
  await clearPendingSuggestions(page);
  await clearScheduleBlocksForDay(page, getCurrentBackendDay());

  await createScheduleBlockViaApi(page, {
    dayOfWeek: getCurrentBackendDay(),
    startTime: "09:00",
    endTime: "10:00",
    activity: "오늘 첫 일정 확인",
    category: "WORK",
    note: "Dashboard stacked hierarchy e2e seed",
  });

  await page.setViewportSize({ width: 390, height: 1000 });
  await page.goto("/dashboard");

  await expect(page.getByRole("heading", { name: /오늘 일정은/ }).first()).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByRole("heading", { name: "오늘 일정 핵심", exact: true })).toBeVisible();
  await expect(page.locator(".ai-approval-card")).toHaveCount(0);
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
  await expectVerticalOrder(page, [".today-flow-card", ".today-schedule-card"]);
});

test("stacked today view keeps today's schedule before pending change actions", async ({
  page,
}, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo, { connectGoogle: true, writeCapable: false });
  await completeOnboardingIfPresent(page);
  await clearPendingSuggestions(page);
  const pendingSuggestion = await ensurePendingSuggestion(page);
  await clearScheduleBlocksForDay(page, getCurrentBackendDay());

  await createScheduleBlockViaApi(page, {
    dayOfWeek: getCurrentBackendDay(),
    startTime: "10:30",
    endTime: "11:30",
    activity: "승인 전 오늘 일정 유지",
    category: "WORK",
    note: "Dashboard pending hierarchy e2e seed",
  });

  await page.setViewportSize({ width: 390, height: 1000 });
  await page.goto("/dashboard");

  await expect(page.getByRole("heading", { name: /오늘 일정은/ }).first()).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByRole("heading", { name: /최적화 제안|확인이 필요합니다\.|지금은 적용할 수 없습니다./ })).toBeVisible();
  await expectPendingSuggestionAction(page, pendingSuggestion);
  await expect(page.getByText("승인 전에는 앱 일정이나 Google 캘린더와 할 일을 바꾸지 않습니다.")).toHaveCount(0);
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
  await expectVerticalOrder(page, [".today-flow-card", ".today-schedule-card", ".ai-approval-card"]);
});
