import { expect, test } from "@playwright/test";

import {
  buildActiveScheduleBlock,
  clearScheduleBlocksForDay,
  completeOnboardingIfPresent,
  createScheduleBlockViaApi,
  getCurrentBackendDay,
  loginAsUniqueMockUser,
} from "./helpers";

test("focus view renders schedule-only current context", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  await clearScheduleBlocksForDay(page, getCurrentBackendDay());
  const block = buildActiveScheduleBlock(`E2E 현재 루틴 ${Date.now()}`);
  await createScheduleBlockViaApi(page, block);

  await page.goto("/focus");

  await expect(page.getByRole("heading", { name: block.activity })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByText(/일정표 기준 다음 일정|실제 집중 상태/).first()).toBeVisible();
  await expect(page.getByText("계산 중")).toHaveCount(0);
  await expect(page.getByText(/남은 시간|추천 집중|다음 일정/).first()).toBeVisible();
});
