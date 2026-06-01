import { expect, test } from "@playwright/test";

import { completeOnboardingIfPresent, loginAsUniqueMockUser } from "./helpers";

test("onboarding completion is backed by server state, not localStorage handoff", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  await expect(page).toHaveURL(/\/dashboard(?:$|\?)/, { timeout: 30_000 });
  const storageKeys = await page.evaluate(() => Object.keys(window.localStorage));
  expect(storageKeys.filter((key) => /onboarding|answers|profile|handoff/i.test(key))).toEqual([]);

  await page.evaluate(() => window.localStorage.clear());
  await page.reload();
  await expect(page).toHaveURL(/\/dashboard(?:$|\?)/, { timeout: 30_000 });
  await expect(page.getByRole("heading", { name: /오늘 일정은|오늘 예정된 일정이 없습니다/ }).first()).toBeVisible({
    timeout: 30_000,
  });
});
