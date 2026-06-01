import { expect, test } from "@playwright/test";

import {
  completeOnboardingIfPresent,
  createScheduleBlockViaApi,
  getCurrentBackendDay,
  loginAsUniqueMockUser,
} from "./helpers";

test("mobile weekly schedule renders cards without native details accordions", async ({ page }, testInfo) => {
  await page.setViewportSize({ width: 390, height: 900 });
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  await createScheduleBlockViaApi(page, {
    dayOfWeek: getCurrentBackendDay(),
    startTime: "15:10",
    endTime: "15:40",
    activity: "모바일 주간 카드 검증",
    category: "WORK",
    note: "모바일에서 접힌 details 없이 바로 읽혀야 합니다.",
  });

  await page.goto("/schedule");
  await expect(page.locator(".mobile-week-agenda")).toBeVisible({ timeout: 30_000 });
  await expect(page.locator("details.mobile-day-details")).toHaveCount(0);
  await expect(page.locator(".mobile-day-card")).toHaveCount(7);
  await expect(page.locator(".mobile-day-card").filter({ hasText: "모바일 주간 카드 검증" })).toBeVisible();
});
