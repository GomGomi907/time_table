import { expect, test } from "@playwright/test";

import {
  backendFetch,
  clearScheduleBlocksForDay,
  completeOnboardingIfPresent,
  createScheduleBlockViaApi,
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
  const cancelButton = confirmDialog.getByRole("button", { name: "취소" });
  const deleteButton = confirmDialog.getByRole("button", { name: "삭제" });
  await expect(cancelButton).toBeFocused();
  await page.keyboard.press("Shift+Tab");
  await expect(deleteButton).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(cancelButton).toBeFocused();
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
    await route.fallback();
  });
  const pendingDelete = finalConfirmDialog.getByRole("button", { name: "삭제" });
  const deleteResponse = page.waitForResponse((response) =>
    response.url().includes("/api/schedule/blocks/") && response.request().method() === "DELETE",
  );
  await pendingDelete.click();
  await expect(pendingDelete).toBeDisabled();
  await expect(finalConfirmDialog.getByRole("button", { name: "취소" })).toBeDisabled();
  await deleteResponse;
  await page.unroute("**/api/schedule/blocks/*");
  await expect(finalConfirmDialog).toHaveCount(0);

  await expect(page.getByRole("button", { name: editedTitlePattern })).toHaveCount(0, {
    timeout: 15_000,
  });
  const remainingWeek = await backendFetch<{ week: Array<{ blocks: Array<{ activity: string }> }> }>(page, "/api/schedule/week");
  expect(remainingWeek.week.flatMap((day) => day.blocks).some((block) => block.activity === editedTitle)).toBe(false);
});

test("pending schedule conflict blocks editing before the create dialog opens", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  await page.route("**/api/schedule/conflicts/preflight", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        pending: true,
        message: "E2E 충돌 해결이 먼저 필요합니다.",
      }),
    });
  });

  await page.goto("/schedule");
  const blockingConflict = page.getByRole("dialog", { name: /일정 변경을 잠시 멈췄습니다/ });
  await expect(blockingConflict).toBeVisible({ timeout: 30_000 });
  await expect(blockingConflict.getByText("E2E 충돌 해결이 먼저 필요합니다.")).toBeVisible();
  await blockingConflict.getByTestId("schedule-conflict-blocking-dismiss").click();
  await expect(blockingConflict).toHaveCount(0);

  await page.getByTestId("schedule-add-button").click();
  await expect(blockingConflict).toBeVisible();
  await expect(page.getByRole("dialog", { name: /새 블록 추가/ })).toHaveCount(0);
});

test("schedule AI chat renders newest backend suggestions as chronological bottom-anchored turns", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  const suggestions = Array.from({ length: 10 }, (_value, index) => buildReadonlySuggestion(index + 1)).reverse();
  await page.route("**/api/agent/suggestions", async (route) => {
    if (route.request().method() !== "GET") {
      await route.fallback();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ success: true, data: suggestions, meta: {} }),
    });
  });

  await page.goto("/schedule");
  const chatLog = page.getByRole("log", { name: "일정 변경 요청 대화" });
  await expect(chatLog).toBeVisible({ timeout: 30_000 });
  const turns = chatLog.locator(".ai-chat-turn");
  await expect(turns).toHaveCount(8);
  await expect(turns.first()).toContainText("요청 3");
  await expect(turns.last()).toContainText("요청 10");
  await expect.poll(async () =>
    chatLog.evaluate((element) => element.scrollHeight - element.clientHeight - element.scrollTop)
  ).toBeLessThanOrEqual(8);

  await page.unroute("**/api/agent/suggestions");
});

test("schedule AI chat starts blank when backend only returns stale suggestions from a previous page entry", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  const staleSuggestions = Array.from({ length: 3 }, (_value, index) =>
    buildReadonlySuggestion(index + 1, false, "2026-06-02T00:00:00.000Z", "pending"),
  ).reverse();
  await page.route("**/api/agent/suggestions", async (route) => {
    if (route.request().method() !== "GET") {
      await route.fallback();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ success: true, data: staleSuggestions, meta: {} }),
    });
  });

  await page.goto("/schedule");
  const chatLog = page.getByRole("log", { name: "일정 변경 요청 대화" });
  await expect(chatLog).toBeVisible({ timeout: 30_000 });
  await expect(chatLog.locator(".ai-chat-turn")).toHaveCount(0);
  await expect(chatLog.getByText("아직 대화가 없습니다.")).toBeVisible();
  await expect(page.getByTestId("schedule-pending-count")).toHaveText("대기 없음");

  await page.unroute("**/api/agent/suggestions");
});

test("schedule AI chat does not force-scroll when the user is reading older turns", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  let suggestions = Array.from({ length: 10 }, (_value, index) => buildReadonlySuggestion(index + 1, true)).reverse();
  await page.route("**/api/agent/suggestions", async (route) => {
    if (route.request().method() !== "GET") {
      await route.fallback();
      return;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ success: true, data: suggestions, meta: {} }),
    });
  });
  await page.route("**/api/agent/reschedule", async (route) => {
    if (route.request().method() !== "POST") {
      await route.fallback();
      return;
    }
    const createdSuggestion = buildReadonlySuggestion(11, true, "2026-06-02T00:00:00.000Z");
    suggestions = [createdSuggestion, ...suggestions];
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ success: true, data: createdSuggestion, meta: {} }),
    });
  });

  await page.goto("/schedule");
  const chatLog = page.getByRole("log", { name: "일정 변경 요청 대화" });
  await expect(chatLog.locator(".ai-chat-turn")).toHaveCount(8);
  await chatLog.evaluate((element) => {
    element.style.maxHeight = "220px";
    element.style.overflowY = "auto";
  });
  await chatLog.evaluate((element) => {
    element.scrollTop = element.scrollHeight;
    element.dispatchEvent(new Event("scroll", { bubbles: true }));
  });
  await expect.poll(async () =>
    chatLog.evaluate((element) => element.scrollHeight - element.clientHeight - element.scrollTop)
  ).toBeLessThanOrEqual(8);

  await chatLog.evaluate((element) => {
    element.scrollTop = 0;
    element.dispatchEvent(new Event("scroll", { bubbles: true }));
  });
  await expect.poll(async () => chatLog.evaluate((element) => element.scrollTop)).toBeLessThanOrEqual(8);

  await page.getByTestId("schedule-ai-request-input").fill("다른 답변도 보여줘");
  await page.getByRole("button", { name: "요청 보내기" }).click();
  await expect(chatLog.locator(".ai-chat-turn").last()).toContainText("요청 11");
  await expect.poll(async () => chatLog.evaluate((element) => element.scrollTop)).toBeLessThanOrEqual(8);
  await expect.poll(async () =>
    chatLog.evaluate((element) => element.scrollHeight - element.clientHeight - element.scrollTop)
  ).toBeGreaterThan(48);

  await page.unroute("**/api/agent/suggestions");
  await page.unroute("**/api/agent/reschedule");
});

test("schedule create blocks duplicate synchronous submits while the mutation is pending", async ({ page }, testInfo) => {
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

  let createRequests = 0;
  await page.route("**/api/schedule/blocks", async (route) => {
    if (route.request().method() !== "POST") {
      await route.fallback();
      return;
    }

    createRequests += 1;
    await new Promise((resolve) => setTimeout(resolve, 500));
    await route.fallback();
  });

  const title = `E2E 중복 제출 방지 ${Date.now()}`;
  await page.getByTestId("schedule-add-button").click();
  const createDialog = page.getByRole("dialog", { name: /새 블록 추가/ });
  await expect(createDialog).toBeVisible();
  await createDialog.getByLabel("요일").selectOption(currentDay);
  await createDialog.getByLabel("시작").fill("12:20");
  await createDialog.getByLabel("종료").fill("12:50");
  await createDialog.getByLabel("활동").fill(title);
  await createDialog.getByLabel("카테고리").selectOption("GROWTH");

  const submitButton = createDialog.getByRole("button", { name: "블록 추가" });
  await submitButton.evaluate((button) => {
    const element = button as HTMLButtonElement;
    element.click();
    element.click();
  });
  await expect(submitButton).toBeDisabled();

  const createdBlock = page.locator(".schedule-block").filter({ hasText: title }).first();
  await expect(createdBlock).toBeVisible({ timeout: 15_000 });
  expect(createRequests).toBe(1);

  await page.unroute("**/api/schedule/blocks");
  await clearScheduleBlocksForDay(page, currentDay);
});

test("schedule edit and delete block duplicate synchronous submits while mutations are pending", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  await page.goto("/schedule");
  await expect(page.getByTestId("schedule-add-button")).toBeVisible({
    timeout: 30_000,
  });
  const currentDay = getCurrentBackendDay();
  await clearScheduleBlocksForDay(page, currentDay);

  const title = `E2E 수정 삭제 중복 방지 ${Date.now()}`;
  const editedTitle = `${title} 수정`;
  await createScheduleBlockViaApi(page, {
    dayOfWeek: currentDay,
    startTime: "13:20",
    endTime: "13:50",
    activity: title,
    category: "GROWTH",
    note: "Playwright duplicate mutation seed",
  });

  await page.reload();
  const seededBlock = page.locator(".schedule-block").filter({ hasText: title }).first();
  await expect(seededBlock).toBeVisible({ timeout: 15_000 });

  let updateRequests = 0;
  let deleteRequests = 0;
  await page.route("**/api/schedule/blocks/*", async (route) => {
    const method = route.request().method();
    if (method === "PUT") {
      updateRequests += 1;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    if (method === "DELETE") {
      deleteRequests += 1;
      await new Promise((resolve) => setTimeout(resolve, 500));
    }
    await route.fallback();
  });

  await seededBlock.click();
  const editDialog = page.getByRole("dialog", { name: /일정 블록 수정/ });
  await expect(editDialog).toBeVisible();
  await editDialog.getByLabel("활동").fill(editedTitle);
  const saveButton = editDialog.getByRole("button", { name: "변경 저장" });
  await saveButton.evaluate((button) => {
    const element = button as HTMLButtonElement;
    element.click();
    element.click();
  });
  await expect(saveButton).toBeDisabled();

  const editedBlock = page.locator(".schedule-block").filter({ hasText: editedTitle }).first();
  await expect(editedBlock).toBeVisible({ timeout: 15_000 });
  await expect.poll(() => updateRequests).toBe(1);

  await editedBlock.click();
  const reopenedEditDialog = page.getByRole("dialog", { name: /일정 블록 수정/ });
  await expect(reopenedEditDialog).toBeVisible();
  await reopenedEditDialog.getByRole("button", { name: "삭제" }).click();
  const confirmDialog = page.getByRole("dialog", { name: /일정 블록을 삭제할까요/ });
  await expect(confirmDialog).toBeVisible();
  const deleteButton = confirmDialog.getByRole("button", { name: "삭제" });
  await deleteButton.evaluate((button) => {
    const element = button as HTMLButtonElement;
    element.click();
    element.click();
  });
  await expect(deleteButton).toBeDisabled();

  await expect(confirmDialog).toHaveCount(0, { timeout: 15_000 });
  await expect.poll(() => deleteRequests).toBe(1);
  await expect(page.getByRole("button", { name: new RegExp(escapeRegExp(editedTitle)) })).toHaveCount(0);

  await page.unroute("**/api/schedule/blocks/*");
});

function buildReadonlySuggestion(
  index: number,
  verbose = false,
  createdAt?: string,
  status: "pending" | "rejected" = "rejected",
) {
  const paddedDetail = verbose
    ? ` ${"이전 대화 맥락을 읽는 중인 사용자의 스크롤 위치를 보존해야 합니다.".repeat(12)}`
    : "";
  return {
    id: `suggestion-${index}`,
    triggerType: "manual_request",
    status,
    statusLabel: status === "pending" ? "검토 대기" : "보류됨",
    statusDetail: "테스트 읽기 전용 제안",
    summary: `답변 ${index}${paddedDetail}`,
    reason: `요청 ${index}${paddedDetail}`,
    explanation: `답변 ${index}${paddedDetail}`,
    commandBatch: {
      summary: `답변 ${index}`,
      explanation: `답변 ${index}`,
      commands: [
        {
          actionType: "explain_only",
          targetType: "none",
          targetId: null,
          payload: {},
          reason: `답변 ${index}`,
          executable: false,
        },
      ],
    },
    previewItems: [
      {
        actionType: "explain_only",
        targetType: "none",
        targetId: null,
        title: `답변 ${index}`,
        detail: null,
        reason: `답변 ${index}`,
        executable: false,
      },
    ],
    executableCommandCount: 0,
    executable: false,
    executionSummary: null,
    createdAt: createdAt ?? new Date(Date.now() + 60_000 + index * 1_000).toISOString(),
    appliedAt: null,
    rejectedAt: null,
    revertedAt: null,
  };
}
