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
  await expect(page.getByRole("heading", { name: /오늘 브리핑/ }).first()).toBeVisible();
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
