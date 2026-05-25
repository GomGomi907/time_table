import { expect, test } from "@playwright/test";

import { loginThroughStartButton } from "./helpers";

test("login start follows mock login and routes to onboarding or dashboard", async ({ page }) => {
  await loginThroughStartButton(page);

  await expect(page).toHaveURL(/\/(onboarding|dashboard)(?:$|\?)/);
  await expect(page.getByText(/일정 조정|오늘 브리핑|주간 일정|생활 리듬/).first()).toBeVisible();
});
