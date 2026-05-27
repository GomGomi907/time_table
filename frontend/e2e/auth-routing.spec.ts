import { expect, test } from "@playwright/test";

import { loginThroughStartButton } from "./helpers";

test("login start follows mock login and routes to onboarding or dashboard", async ({ page }) => {
  await loginThroughStartButton(page);

  await expect(page).toHaveURL(/\/(onboarding|dashboard)(?:$|\?)/);
  await expect(page.getByText(/오늘 일정|주간 일정|생활 리듬|처음 설정/).first()).toBeVisible();
});
