import { expect, test } from "@playwright/test";

import { completeOnboardingIfPresent, loginAsUniqueMockUser } from "./helpers";

function agendaOccurrence(
  occurrenceId: string,
  title: string,
  startAt: string,
  endAt: string,
  entityType: "EVENT" | "TASK" | "ROUTINE_BLOCK" = "EVENT",
) {
  return {
    occurrenceId,
    entityType,
    entityId: occurrenceId,
    seriesId: occurrenceId,
    startAt,
    endAt,
    title,
    category: entityType === "ROUTINE_BLOCK" ? "WORK" : null,
    sourceType: entityType === "TASK" ? "TASKS" : "GOOGLE_CALENDAR",
    syncState: "SYNCED",
    recurrenceInstanceType: "SINGLE",
    priorityTier: entityType === "TASK" ? 20 : 10,
    collisionPolicy: "CAN_BE_SHADOWED",
    providerAuthority: entityType === "ROUTINE_BLOCK" ? "LOCAL_ROUTINE_ONLY" : "REMOTE_FIXED",
    shadowState: "NONE",
    shadowingEntityType: null,
    shadowingEntityId: null,
    protectedWindow: false,
    synthetic: entityType === "ROUTINE_BLOCK",
  };
}

test("schedule agenda stream groups calendar range occurrences by local date in chronological order", async ({
  page,
}, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  await page.route("**/api/calendar/range**", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        success: true,
        data: {
          start: "2026-01-01T15:00:00.000Z",
          end: "2026-01-15T15:00:00.000Z",
          view: "AGENDA",
          timezone: "Asia/Seoul",
          occurrences: [
            agendaOccurrence("event-late", "오전 제품 리뷰", "2026-01-02T01:00:00.000Z", "2026-01-02T02:00:00.000Z"),
            agendaOccurrence("task-next-day", "다음날 성장 과제", "2026-01-03T00:00:00.000Z", "2026-01-03T00:30:00.000Z", "TASK"),
            agendaOccurrence("routine-early", "아침 루틴 정리", "2026-01-01T23:00:00.000Z", "2026-01-01T23:30:00.000Z", "ROUTINE_BLOCK"),
          ],
          instrumentation: {
            repositoryGroupCount: 3,
            repositoryGroups: ["events", "tasks", "routineBlocks"],
            occurrenceCount: 3,
            rangeDays: 14,
          },
        },
      }),
    });
  });

  await page.goto("/schedule");
  await expect(page.getByTestId("agenda-stream")).toBeVisible({ timeout: 30_000 });

  const groups = page.getByTestId("agenda-day-group");
  await expect(groups).toHaveCount(2);
  await expect(groups.nth(0)).toContainText("아침 루틴 정리");
  await expect(groups.nth(0)).toContainText("오전 제품 리뷰");
  await expect(groups.nth(1)).toContainText("다음날 성장 과제");

  const orderedTitles = await page.getByTestId("agenda-occurrence").evaluateAll((items) =>
    items.map((item) => item.textContent ?? ""),
  );
  expect(orderedTitles.join("\n")).toMatch(/아침 루틴 정리[\s\S]*오전 제품 리뷰[\s\S]*다음날 성장 과제/);
});
