import { expect, test } from "@playwright/test";

import {
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
  await expect(page.getByRole("button", { name: "일정 직접 추가" })).toBeVisible({
    timeout: 30_000,
  });
  const currentDay = getCurrentBackendDay();
  await clearScheduleBlocksForDay(page, currentDay);
  await page.reload();
  await expect(page.getByRole("button", { name: "일정 직접 추가" })).toBeVisible({
    timeout: 30_000,
  });

  const title = `E2E 새 블록 ${Date.now()}`;
  const editedTitle = `${title} 수정`;
  const titlePattern = new RegExp(escapeRegExp(title));
  const editedTitlePattern = new RegExp(escapeRegExp(editedTitle));

  await page.getByRole("button", { name: "일정 직접 추가" }).click();

  const createDialog = page.getByRole("dialog", { name: /새 블록 추가/ });
  await expect(createDialog).toBeVisible();
  await createDialog.getByLabel("요일").selectOption(currentDay);
  await createDialog.getByLabel("시작").fill("11:10");
  await createDialog.getByLabel("종료").fill("11:40");
  await createDialog.getByLabel("활동").fill(title);
  await createDialog.getByLabel("카테고리").selectOption("GROWTH");
  await createDialog.getByLabel("메모").fill("Playwright CRUD seed");
  await createDialog.getByRole("button", { name: "블록 추가" }).click();

  const createdBlock = page.getByRole("button", { name: titlePattern });
  await expect(createdBlock).toBeVisible({ timeout: 15_000 });
  await createdBlock.click();

  const editDialog = page.getByRole("dialog", { name: /일정 블록 수정/ });
  await expect(editDialog).toBeVisible();
  await editDialog.getByLabel("활동").fill(editedTitle);
  await editDialog.getByRole("button", { name: "변경 저장" }).click();

  const editedBlock = page.getByRole("button", { name: editedTitlePattern });
  await expect(editedBlock).toBeVisible({ timeout: 15_000 });
  await editedBlock.click();

  page.once("dialog", (dialog) => dialog.accept());
  await page.getByRole("dialog", { name: /일정 블록 수정/ }).getByRole("button", { name: "삭제" }).click();

  await expect(page.getByRole("button", { name: editedTitlePattern })).toHaveCount(0, {
    timeout: 15_000,
  });
});
