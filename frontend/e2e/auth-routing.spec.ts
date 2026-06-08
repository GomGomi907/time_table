import { expect, test } from "@playwright/test";

import { loginThroughStartButton } from "./helpers";

test("login start follows mock login and routes to onboarding or dashboard", async ({ page }) => {
  await loginThroughStartButton(page);

  await expect(page).toHaveURL(/\/(onboarding|dashboard)(?:$|\?)/);
  await expect(page.getByText(/오늘 일정|주간 일정|생활 리듬|처음 설정/).first()).toBeVisible();
});

test("auth callback shows a clear retry when onboarding status cannot load", async ({ page }) => {
  await page.route("**/api/onboarding/status", async (route) => {
    await route.fulfill({
      status: 500,
      contentType: "application/json",
      body: JSON.stringify({
        message: "처음 설정 상태를 불러오지 못했습니다.",
      }),
    });
  });

  await loginThroughStartButton(page, { finalUrl: /\/auth\/callback(?:$|\?)/ });

  await expect(page).toHaveURL(/\/auth\/callback(?:$|\?)/, { timeout: 30_000 });
  await expect(page.getByRole("heading", { name: "처음 설정 상태를 확인하지 못했습니다." })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByRole("button", { name: "상태 다시 확인" })).toBeVisible();
});
