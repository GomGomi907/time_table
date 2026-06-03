import { expect, test } from "@playwright/test";

import { completeOnboardingIfPresent, loginAsUniqueMockUser } from "./helpers";

function agendaOccurrence(
  occurrenceId: string,
  title: string,
  startAt: string | null,
  endAt: string | null,
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
            agendaOccurrence("multi-day-release", "밤샘 출시 점검", "2026-01-02T14:00:00.000Z", "2026-01-03T02:00:00.000Z"),
            agendaOccurrence("task-next-day", "다음날 성장 과제", "2026-01-03T00:00:00.000Z", "2026-01-03T00:30:00.000Z", "TASK"),
            agendaOccurrence("routine-early", "아침 루틴 정리", "2026-01-01T23:00:00.000Z", "2026-01-01T23:30:00.000Z", "ROUTINE_BLOCK"),
            agendaOccurrence("unscheduled-idea", "날짜 미정 아이디어", null, null, "TASK"),
          ],
          instrumentation: {
            repositoryGroupCount: 3,
            repositoryGroups: ["events", "tasks", "routineBlocks"],
            occurrenceCount: 5,
            rangeDays: 14,
          },
        },
      }),
    });
  });

  await page.goto("/schedule");
  await page.getByTestId("schedule-view-option-agenda").click();
  await expect(page.getByTestId("agenda-stream")).toBeVisible({ timeout: 30_000 });

  const groups = page.getByTestId("agenda-day-group");
  await expect(groups).toHaveCount(3);
  await expect(groups.nth(0)).toContainText("아침 루틴 정리");
  await expect(groups.nth(0)).toContainText("오전 제품 리뷰");
  await expect(groups.nth(0)).toContainText("밤샘 출시 점검");
  await expect(groups.nth(1)).toContainText("밤샘 출시 점검");
  await expect(groups.nth(1)).toContainText("다음날 성장 과제");
  await expect(groups.nth(2)).toContainText("날짜 미정");
  await expect(groups.nth(2)).toContainText("날짜 미정 아이디어");
  await expect(groups.nth(2).locator(".agenda-day-head")).toBeDisabled();
  await expect(page.getByTestId("agenda-occurrence")).toHaveCount(6);

  const orderedTitles = await page.getByTestId("agenda-occurrence").evaluateAll((items) =>
    items.map((item) => item.textContent ?? ""),
  );
  expect(orderedTitles.join("\n")).toMatch(/아침 루틴 정리[\s\S]*오전 제품 리뷰[\s\S]*밤샘 출시 점검[\s\S]*밤샘 출시 점검[\s\S]*다음날 성장 과제/);

  await groups.nth(0).locator(".agenda-day-head").click();
  await expect(page.getByTestId("selected-day-timeline")).toBeVisible();
  await expect(page.getByTestId("schedule-view-option-day")).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByTestId("selected-day-occurrence")).toContainText(["아침 루틴 정리", "오전 제품 리뷰", "밤샘 출시 점검"]);
});


test("schedule monthly mosaic summarizes range days and hands selected date to day view", async ({
  page,
}, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  await page.route("**/api/calendar/range**", async (route) => {
    const url = new URL(route.request().url());
    const view = url.searchParams.get("view")?.toUpperCase() ?? "MONTH";
    const start = url.searchParams.get("start") ?? "2026-06-01T00:00:00.000Z";
    const anchor = new Date(start);
    anchor.setUTCDate(anchor.getUTCDate() + 1);
    const end = new Date(anchor);
    end.setUTCMinutes(end.getUTCMinutes() + 45);

    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        success: true,
        data: {
          start,
          end: url.searchParams.get("end") ?? "2026-07-01T00:00:00.000Z",
          view,
          timezone: "Asia/Seoul",
          occurrences: [
            agendaOccurrence(
              "monthly-focus",
              "월간 핵심 집중",
              anchor.toISOString(),
              end.toISOString(),
              "TASK",
            ),
          ],
          instrumentation: {
            repositoryGroupCount: 3,
            repositoryGroups: ["events", "tasks", "routineBlocks"],
            occurrenceCount: 1,
            rangeDays: 31,
          },
        },
      }),
    });
  });

  await page.goto("/schedule");
  await page.getByTestId("schedule-view-option-month").click();
  await expect(page.getByTestId("monthly-mosaic")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("monthly-mosaic")).toContainText("월간 핵심 집중");

  await page.getByTestId("monthly-mosaic-day").filter({ hasText: "월간 핵심 집중" }).click();
  await expect(page.getByTestId("selected-day-timeline")).toBeVisible();
  await expect(page.getByTestId("schedule-view-option-day")).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByTestId("selected-day-occurrence")).toContainText("월간 핵심 집중");
});

test("schedule renders pending AI draft projection above the timeline", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  const draftStart = new Date();
  draftStart.setSeconds(0, 0);
  const draftEnd = new Date(draftStart.getTime() + 30 * 60 * 1000);

  await page.route("**/api/calendar/range**", async (route) => {
    const url = new URL(route.request().url());
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        success: true,
        data: {
          start: url.searchParams.get("start") ?? draftStart.toISOString(),
          end: url.searchParams.get("end") ?? draftEnd.toISOString(),
          view: url.searchParams.get("view")?.toUpperCase() ?? "DAY",
          timezone: "Asia/Seoul",
          occurrences: [],
          instrumentation: {
            repositoryGroupCount: 0,
            repositoryGroups: [],
            occurrenceCount: 0,
            rangeDays: 1,
          },
        },
      }),
    });
  });

  await page.route("**/api/agent/suggestions", async (route) => {
    if (route.request().method() !== "GET") {
      await route.fallback();
      return;
    }
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        success: true,
        data: [
          {
            id: "draft-projection-1",
            triggerType: "manual_request",
            status: "pending",
            statusLabel: "대기 중",
            statusDetail: "적용 전",
            summary: "내일 오전 집중 블록 추가",
            reason: "내일 오전 집중 시간을 잡아줘",
            explanation: "빈 시간에 집중 블록을 제안합니다.",
            commandBatch: {
              summary: "집중 블록 추가",
              explanation: "타임라인에 draft로 먼저 투영합니다.",
              commands: [
                {
                  actionType: "create_event",
                  targetType: "event",
                  targetId: null,
                  payload: {
                    title: "AI Draft 집중 시간",
                    startAt: draftStart.toISOString(),
                    endAt: draftEnd.toISOString(),
                  },
                  reason: "가용 시간 확보",
                  executable: true,
                },
              ],
            },
            previewItems: [
              {
                actionType: "create_event",
                targetType: "event",
                targetId: null,
                title: "AI Draft 집중 시간",
                detail: "10:00-10:30",
                reason: "가용 시간 확보",
                executable: true,
              },
            ],
            executableCommandCount: 1,
            executable: true,
            executionSummary: null,
            createdAt: new Date(Date.now() + 60_000).toISOString(),
            appliedAt: null,
            rejectedAt: null,
            revertedAt: null,
          },
        ],
      }),
    });
  });

  await page.goto("/schedule");
  await expect(page.getByTestId("ai-draft-projection")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("ai-draft-projection")).toContainText("AI Draft 집중 시간");

  await page.getByTestId("schedule-view-option-month").click();
  await expect(page.getByTestId("monthly-draft-badge").first()).toContainText(/AI Draft|AI Draft 집중 시간/, { timeout: 30_000 });

  await page.getByTestId("schedule-view-option-agenda").click();
  await expect(page.getByTestId("agenda-draft-occurrence")).toContainText("AI Draft 집중 시간", { timeout: 30_000 });

  await page.getByTestId("schedule-view-option-day").click();
  await expect(page.getByTestId("selected-day-draft-occurrence")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("selected-day-draft-occurrence")).toContainText("AI Draft · 적용 전");
});

test("schedule range event occurrence can be opened and patched from the day timeline", async ({
  page,
}, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  const originalTitle = "편집 전 원본 이벤트";
  const editedTitle = "편집 후 원본 이벤트";
  let patchBody: Record<string, unknown> | null = null;

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
            agendaOccurrence("event-edit", originalTitle, "2026-01-02T01:00:00.000Z", "2026-01-02T02:00:00.000Z"),
          ],
          instrumentation: {
            repositoryGroupCount: 1,
            repositoryGroups: ["events"],
            occurrenceCount: 1,
            rangeDays: 14,
          },
        },
      }),
    });
  });

  await page.route("**/api/events/event-edit", async (route) => {
    if (route.request().method() === "GET") {
      await route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({
          success: true,
          data: {
            id: "event-edit",
            title: originalTitle,
            description: "원본 설명",
            startAt: "2026-01-02T01:00:00.000Z",
            endAt: "2026-01-02T02:00:00.000Z",
            actualStartAt: null,
            actualEndAt: null,
            priority: 3,
            status: "PLANNED",
            category: "WORK",
            sourceType: "GOOGLE_CALENDAR",
            syncState: "SYNCED",
            goalId: "11111111-1111-1111-1111-111111111111",
            externalSourceId: "google-event-edit",
          },
        }),
      });
      return;
    }
    if (route.request().method() === "PATCH") {
      patchBody = route.request().postDataJSON() as Record<string, unknown>;
      await route.fulfill({
        contentType: "application/json",
        body: JSON.stringify({ success: true, data: { ...patchBody, id: "event-edit" } }),
      });
      return;
    }
    await route.fallback();
  });

  await page.goto("/schedule");
  await page.getByTestId("schedule-view-option-agenda").click();
  await expect(page.getByTestId("agenda-stream")).toBeVisible({ timeout: 30_000 });
  await page.getByTestId("agenda-occurrence").filter({ hasText: originalTitle }).locator("button").click();

  const editDialog = page.getByRole("dialog", { name: /캘린더 이벤트 수정/ });
  await expect(editDialog).toBeVisible();
  await editDialog.getByLabel("제목").fill(editedTitle);
  await editDialog.getByRole("button", { name: "원본 저장" }).click();

  await expect(editDialog).toHaveCount(0, { timeout: 15_000 });
  expect(patchBody?.title).toBe(editedTitle);
  expect(patchBody?.startAt).toBe("2026-01-02T01:00:00.000Z");
  expect(patchBody?.endAt).toBe("2026-01-02T02:00:00.000Z");
  expect(patchBody?.goalId).toBe("11111111-1111-1111-1111-111111111111");
});
