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
}

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
  await expect(page.getByRole("heading", { name: "오늘 일정 핵심", exact: true })).toBeVisible();
  const scheduleCardText = await page.locator(".today-schedule-card").innerText();
  expect(scheduleCardText).toMatch(/아침 계획 정리|제품 리뷰 회의|개발 집중 블록|오늘 할 일 정리|성장 과제|저녁 운동/);
  await expect(page.locator(".today-schedule-card").getByText(/나머지 \d+개 일정은 접어 두었습니다/)).toBeVisible();
  await expect(page.getByText(/승인 전에는|승인할 조정안은 없습니다|승인 전 안전/).first()).toHaveCount(0);
});

test("stacked briefing keeps today's schedule before quiet AI status when no approval is pending", async ({
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
  await expectVerticalOrder(page, [".today-flow-card", ".today-schedule-card"]);
});

test("stacked briefing keeps today's schedule before pending approval with visible actions", async ({
  page,
}, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo, { connectGoogle: true, writeCapable: false });
  await completeOnboardingIfPresent(page);
  await clearPendingSuggestions(page);
  await ensurePendingSuggestion(page);
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
  await expect(page.getByRole("heading", { name: "적용 전 조정안을 확인해보세요." })).toBeVisible();
  await expect(page.getByRole("button", { name: "보류" })).toBeVisible();
  await expect(page.getByRole("button", { name: "승인 적용" })).toBeVisible();
  await expect(page.getByText("승인 전에는 앱 일정이나 Google 캘린더와 할 일을 바꾸지 않습니다.")).toHaveCount(0);
  await expectVerticalOrder(page, [".today-flow-card", ".today-schedule-card", ".ai-approval-card"]);
});
