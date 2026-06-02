import { expect, test, type Locator } from "@playwright/test";

import {
  assertNoInternalUserCopy,
  backendFetch,
  buildActiveScheduleBlock,
  clearAllScheduleBlocks,
  completeOnboardingIfPresent,
  loginAsUniqueMockUser,
} from "./helpers";

async function expectSingleLineTime(locator: Locator) {
  await expect(locator).toBeVisible();
  const metrics = await locator.evaluate((element) => {
    const style = window.getComputedStyle(element);
    const rect = element.getBoundingClientRect();
    const lineHeight = Number.parseFloat(style.lineHeight);
    const fontSize = Number.parseFloat(style.fontSize);
    return {
      height: rect.height,
      lineHeight: Number.isFinite(lineHeight) ? lineHeight : fontSize * 1.2,
      text: element.textContent ?? "",
      whiteSpace: style.whiteSpace,
    };
  });

  expect(metrics.whiteSpace, `${metrics.text} should not wrap`).toBe("nowrap");
  expect(metrics.height, `${metrics.text} should fit on one rendered line`).toBeLessThanOrEqual(
    metrics.lineHeight * 1.35,
  );
}

test("focus view renders schedule-only current context", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);
  await clearAllScheduleBlocks(page);

  const block = buildActiveScheduleBlock(`E2E 현재 루틴 ${Date.now()}`);
  await backendFetch(page, "/api/schedule/blocks", { method: "POST", body: block });

  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto("/focus");

  await expect(page.getByRole("heading", { name: block.activity })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByText(/진행 중이며|다음 일정|지금 실행/).first()).toBeVisible();
  await expect(page.getByRole("button", { name: "일정 완료" })).toBeVisible();
  await expect(page.getByRole("button", { name: "미루기" })).toBeVisible();
  await expect(page.getByText("계산 중")).toHaveCount(0);
  await expect(page.getByText(/남은 시간|지금 시작|다음 일정/).first()).toBeVisible();
  await expectSingleLineTime(page.locator(".timer-value"));
  await expectSingleLineTime(page.locator(".timer-context"));
  await assertNoInternalUserCopy(page);

  await page.getByRole("button", { name: "미루기" }).click();
  await expect(page.getByRole("status")).toContainText("일정 블록을 30분 미루고 변경 요청을 만들었습니다.", {
    timeout: 30_000,
  });
});
