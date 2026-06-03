import { expect, test } from "@playwright/test";

import {
  clearScheduleBlocksForDay,
  completeOnboardingIfPresent,
  createScheduleBlockViaApi,
  getCurrentBackendDay,
  loginAsUniqueMockUser,
} from "./helpers";

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
  await expect(saveAnswersButton).toHaveText("답변 저장하고 계속");
  await expect(page.getByRole("button", { name: "오늘 일정표 보기" })).toHaveCount(0);
  await expect(saveAnswersButton).toBeEnabled({ timeout: 30_000 });
  await saveAnswersButton.click();

  const completePrimary = page.getByTestId("onboarding-complete-primary");
  await expect(completePrimary).toBeVisible({ timeout: 30_000 });
  await expect(completePrimary).not.toHaveText("오늘 일정표 보기");

  const applySuggestionButton = page.getByRole("button", { name: "추천 일정 적용하고 시작" });
  if (await applySuggestionButton.isVisible({ timeout: 500 }).catch(() => false)) {
    await expect(page.getByRole("button", { name: "적용하지 않고 오늘 화면으로" })).toBeVisible();
    return;
  }

  await expect(page.getByRole("button", { name: "오늘 화면으로 이동" })).toBeVisible();
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
