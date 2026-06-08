import { expect, Page, test } from "@playwright/test";

import {
  assertNoInternalUserCopy,
  backendFetch,
  clearScheduleBlocksForDay,
  completeOnboardingIfPresent,
  createScheduleBlockViaApi,
  getCurrentBackendDay,
  loginAsUniqueMockUser,
} from "./helpers";

interface WeekScheduleForOnboardingTest {
  week: Array<{
    blocks: unknown[];
  }>;
}

async function countScheduleBlocks(page: Page) {
  const week = await backendFetch<WeekScheduleForOnboardingTest>(page, "/api/schedule/week");
  return week.week.reduce((total, day) => total + day.blocks.length, 0);
}

function hostileOnboardingAnswersResponse() {
  return {
    status: {
      googleConnected: false,
      profileReady: true,
      aiExperienceReady: true,
      completed: false,
      nextStep: "review",
      displayName: "E2E Hostile",
      timezone: "Asia/Seoul",
      bootstrappedAt: new Date().toISOString(),
      importSummary: {
        calendarEventCount: 0,
        taskCount: 0,
        scheduleBlockCount: 0,
        goalCount: 0,
        lastCalendarSyncAt: null,
        lastTaskSyncAt: null,
        workspaceSummary: "providerMetadata payload validationTrace",
        sourceLabel: "providerMetadata payload validationTrace",
      },
      questions: [],
      profile: {
        wakeTime: "07:00",
        workStartTime: "09:00",
        dinnerTime: "19:00",
        sleepTime: "23:30",
        weekendStyle: "balanced",
        focusSessionMinutes: "45",
        focusBreakMinutes: "10",
        focusInterventionStyle: "balanced",
        quietHoursStart: "23:30",
        quietHoursEnd: "07:00",
      },
      experience: {
        summary: "providerMetadata payload validationTrace",
        suggestion: { id: "00000000-0000-0000-0000-000000000000" },
        previewItems: [
          {
            title: "payload validationTrace",
            days: "월-금",
            startTime: "08:15",
            endTime: "09:00",
            category: "providerMetadata",
            reason: "providerMetadata payload validationTrace",
          },
        ],
      },
    },
    message: "providerMetadata payload validationTrace",
  };
}

test("new mock user completes onboarding and lands on dashboard", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  await expect(page).toHaveURL(/\/dashboard(?:$|\?)/);
  await expect(page.getByRole("heading", { name: /오늘 일정/ }).first()).toBeVisible();
});

test("onboarding action buttons describe the actual next step and side effect", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await expect(page).toHaveURL(/\/onboarding(?:$|\?)/, { timeout: 30_000 });

  const saveAnswersButton = page.getByTestId("onboarding-continue-button");
  await expect(saveAnswersButton).toBeVisible({ timeout: 45_000 });
  await expect(saveAnswersButton).toHaveText("기본값으로 계속");
  await expect(page.getByRole("button", { name: "오늘 일정표 보기" })).toHaveCount(0);
  await expect(saveAnswersButton).toBeEnabled({ timeout: 30_000 });
  await saveAnswersButton.click();

  const completePrimary = page.getByTestId("onboarding-complete-primary");
  await expect(completePrimary).toBeVisible({ timeout: 30_000 });
  await expect(completePrimary).not.toHaveText("오늘 일정표 보기");

  const applySuggestionButton = page.getByRole("button", { name: /^추천 시간 넣고 일정표 보기$/ });
  if (await applySuggestionButton.isVisible({ timeout: 500 }).catch(() => false)) {
    await expect(page.getByRole("button", { name: /^건너뛰고 일정표 보기$/ })).toBeVisible();
    return;
  }

  await expect(page.getByRole("button", { name: /^일정표 보기$/ })).toBeVisible();
});

test("onboarding review does not render hostile backend internals", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await expect(page).toHaveURL(/\/onboarding(?:$|\?)/, { timeout: 30_000 });

  await page.route("**/api/onboarding/answers", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(hostileOnboardingAnswersResponse()),
    });
  });

  await expect(page.getByTestId("onboarding-continue-button")).toHaveText("기본값으로 계속", {
    timeout: 45_000,
  });
  await page.getByTestId("onboarding-continue-button").click();

  await expect(page.getByLabel("추천 시간 요약")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByLabel("추천 시간 요약")).toContainText("추천 시간 1개를 준비했습니다.");
  await assertNoInternalUserCopy(page);
});

test("onboarding completion applies recommended times only when explicitly chosen", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await expect(page).toHaveURL(/\/onboarding(?:$|\?)/, { timeout: 30_000 });

  await expect(page.getByTestId("onboarding-continue-button")).toHaveText("기본값으로 계속", {
    timeout: 45_000,
  });
  await page.getByTestId("onboarding-continue-button").click();

  const skipButton = page.getByRole("button", { name: /^건너뛰고 일정표 보기$/ });
  const applyButton = page.getByRole("button", { name: /^추천 시간 넣고 일정표 보기$/ });
  await expect(skipButton.or(page.getByRole("button", { name: /^일정표 보기$/ }))).toBeVisible({
    timeout: 30_000,
  });

  if (await applyButton.isVisible({ timeout: 500 }).catch(() => false)) {
    await expect(page.getByLabel("추천 시간 요약")).toBeVisible();
    await applyButton.click();
    await expect(page).toHaveURL(/\/dashboard(?:$|\?)/, { timeout: 30_000 });
    await expect.poll(() => countScheduleBlocks(page)).toBeGreaterThan(0);
    return;
  }

  await page.getByRole("button", { name: /^일정표 보기$/ }).click();
  await expect(page).toHaveURL(/\/dashboard(?:$|\?)/, { timeout: 30_000 });
});

test("skipping onboarding recommendations keeps routine blocks unapplied", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await expect(page).toHaveURL(/\/onboarding(?:$|\?)/, { timeout: 30_000 });

  await expect(page.getByTestId("onboarding-continue-button")).toBeEnabled({ timeout: 45_000 });
  await page.getByTestId("onboarding-continue-button").click();

  const skipButton = page.getByRole("button", { name: /^(건너뛰고 일정표 보기|일정표 보기)$/ });
  await expect(skipButton).toBeVisible({ timeout: 30_000 });
  await skipButton.click();

  await expect(page).toHaveURL(/\/dashboard(?:$|\?)/, { timeout: 30_000 });
  await expect.poll(() => countScheduleBlocks(page)).toBe(0);
});

test("schedule page shows non-sleep routine blocks saved through the API", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  const targetDay = getCurrentBackendDay() === "MONDAY" ? "TUESDAY" : "MONDAY";
  const activity = `E2E 점심 이동 ${Date.now()}`;
  await clearScheduleBlocksForDay(page, targetDay);
  await createScheduleBlockViaApi(page, {
    dayOfWeek: targetDay,
    startTime: "13:10",
    endTime: "13:35",
    activity,
    category: "LIFE",
    note: "low-signal routine must still render from DB",
  });

  await page.goto("/schedule");
  await expect(page.getByRole("button", { name: new RegExp(activity) })).toBeVisible({
    timeout: 30_000,
  });
});
