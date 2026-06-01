import { expect, test } from "@playwright/test";

import {
  backendFetch,
  clearScheduleBlocksForDay,
  completeOnboardingIfPresent,
  escapeRegExp,
  getCurrentBackendDay,
  loginAsUniqueMockUser,
} from "./helpers";

test("schedule block can be created, edited, and deleted", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  await page.goto("/schedule");
  await expect(page.getByTestId("schedule-add-button")).toBeVisible({
    timeout: 30_000,
  });
  const currentDay = getCurrentBackendDay();
  await clearScheduleBlocksForDay(page, currentDay);
  await page.reload();
  await expect(page.getByTestId("schedule-add-button")).toBeVisible({
    timeout: 30_000,
  });

  const title = `E2E 새 블록 ${Date.now()}`;
  const editedTitle = `${title} 수정`;
  const titlePattern = new RegExp(escapeRegExp(title));
  const editedTitlePattern = new RegExp(escapeRegExp(editedTitle));

  await page.getByTestId("schedule-add-button").click();

  const createDialog = page.getByRole("dialog", { name: /새 블록 추가/ });
  await expect(createDialog).toBeVisible();
  await createDialog.getByLabel("요일").selectOption(currentDay);
  await createDialog.getByLabel("시작").fill("11:10");
  await createDialog.getByLabel("종료").fill("11:40");
  await createDialog.getByLabel("활동").fill(title);
  await createDialog.getByLabel("카테고리").selectOption("GROWTH");
  await createDialog.getByLabel("메모").fill("Playwright CRUD seed");
  await createDialog.getByRole("button", { name: "블록 추가" }).click();

  const createdBlock = page.locator(".schedule-block").filter({ hasText: title }).first();
  await expect(createdBlock).toBeVisible({ timeout: 15_000 });
  await createdBlock.click();

  const editDialog = page.getByRole("dialog", { name: /일정 블록 수정/ });
  await expect(editDialog).toBeVisible();
  await editDialog.getByLabel("활동").fill(editedTitle);
  await editDialog.getByRole("button", { name: "변경 저장" }).click();

  const editedBlock = page.locator(".schedule-block").filter({ hasText: editedTitle }).first();
  await expect(editedBlock).toBeVisible({ timeout: 15_000 });
  await editedBlock.click();

  await page.getByRole("dialog", { name: /일정 블록 수정/ }).getByRole("button", { name: "삭제" }).click();
  const confirmDialog = page.getByRole("dialog", { name: /일정 블록을 삭제할까요/ });
  await expect(confirmDialog).toBeVisible();
  await expect(confirmDialog.getByText(editedTitlePattern)).toBeVisible();
  await confirmDialog.getByRole("button", { name: "취소" }).click();
  await expect(page.getByRole("dialog", { name: /일정 블록 수정/ })).toBeVisible();
  await page.getByRole("dialog", { name: /일정 블록 수정/ }).getByRole("button", { name: "삭제" }).click();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog", { name: /일정 블록을 삭제할까요/ })).toHaveCount(0);
  await expect(page.getByRole("dialog", { name: /일정 블록 수정/ })).toBeVisible();
  await page.getByRole("dialog", { name: /일정 블록 수정/ }).getByRole("button", { name: "삭제" }).click();
  const finalConfirmDialog = page.getByRole("dialog", { name: /일정 블록을 삭제할까요/ });
  await expect(finalConfirmDialog).toBeVisible();
  await page.route("**/api/schedule/blocks/*", async (route) => {
    if (route.request().method() !== "DELETE") {
      await route.fallback();
      return;
    }

    await new Promise((resolve) => setTimeout(resolve, 400));
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ ok: true }),
    });
  });
  const pendingDelete = finalConfirmDialog.getByRole("button", { name: "삭제" });
  await pendingDelete.click();
  await expect(pendingDelete).toBeDisabled();
  await expect(finalConfirmDialog.getByRole("button", { name: "취소" })).toBeDisabled();
  await page.unroute("**/api/schedule/blocks/*");
  await expect(finalConfirmDialog).toHaveCount(0);

  await expect(page.getByRole("button", { name: editedTitlePattern })).toHaveCount(0, {
    timeout: 15_000,
  });
  const remainingWeek = await backendFetch<{ week: Array<{ blocks: Array<{ activity: string }> }> }>(page, "/api/schedule/week");
  expect(remainingWeek.week.flatMap((day) => day.blocks).some((block) => block.activity === editedTitle)).toBe(false);
});
