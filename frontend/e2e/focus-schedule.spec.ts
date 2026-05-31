import { expect, test } from "@playwright/test";

import {
  assertNoInternalUserCopy,
  backendFetch,
  buildActiveScheduleBlock,
  clearAllScheduleBlocks,
  completeOnboardingIfPresent,
  loginAsUniqueMockUser,
} from "./helpers";

test("focus view renders schedule-only current context", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);
  await clearAllScheduleBlocks(page);

  const block = buildActiveScheduleBlock(`E2E 현재 루틴 ${Date.now()}`);
  await backendFetch(page, "/api/schedule/blocks", { method: "POST", body: block });

  await page.goto("/focus");

  await expect(page.getByRole("heading", { name: block.activity })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByText(/진행 중이며|다음 일정|지금 실행/).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "일정 완료" })).toBeVisible();
  await expect(page.getByRole("button", { name: "미루기" })).toBeVisible();
  await expect(page.getByText("계산 중")).toHaveCount(0);
  await expect(page.getByText(/남은 시간|지금 시작|다음 일정/).first()).toBeVisible();
  await assertNoInternalUserCopy(page);

  await page.getByRole("button", { name: "미루기" }).click();
  await expect(page.getByRole("status")).toContainText("일정 블록을 30분 미루고 변경 요청을 만들었습니다.", {
    timeout: 30_000,
  });
});
