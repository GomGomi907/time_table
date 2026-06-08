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

const WEEK_DAYS = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

function scheduleWeekResponse(mondayBlocks: Array<{ id: string; startTime: string; endTime: string; activity: string }>) {
  return {
    week: WEEK_DAYS.map((dayOfWeek) => ({
      dayOfWeek,
      blocks: dayOfWeek === "Monday"
        ? mondayBlocks.map((block) => ({
            id: block.id,
            startTime: block.startTime,
            endTime: block.endTime,
            activity: block.activity,
            category: "WORK",
            note: null,
            sourceType: "MANUAL",
            sourceRef: "test",
          }))
        : [],
    })),
  };
}

test("weekly stack renders schedule blocks and calendar occurrences by time, not insertion group", async ({
  page,
}, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  await page.route("**/api/schedule/week", async (route) => {
    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify(scheduleWeekResponse([
        { id: "late-routine", startTime: "10:00", endTime: "11:00", activity: "늦은 루틴 업무" },
        { id: "early-routine", startTime: "08:00", endTime: "08:30", activity: "이른 루틴 업무" },
      ])),
    });
  });

  await page.route("**/api/calendar/range**", async (route) => {
    const url = new URL(route.request().url());
    const rangeStart = new Date(url.searchParams.get("start") ?? "2026-06-07T15:00:00.000Z");
    const localTime = (hour: number, minute: number) =>
      new Date(rangeStart.getTime() + (hour * 60 + minute) * 60_000).toISOString();

    await route.fulfill({
      contentType: "application/json",
      body: JSON.stringify({
        success: true,
        data: {
          start: url.searchParams.get("start") ?? rangeStart.toISOString(),
          end: url.searchParams.get("end") ?? new Date(rangeStart.getTime() + 7 * 24 * 60 * 60_000).toISOString(),
          view: url.searchParams.get("view")?.toUpperCase() ?? "WEEK",
          timezone: "Asia/Seoul",
          occurrences: [
            agendaOccurrence("calendar-overnight", "이어지는 밤샘 점검", localTime(0, -30), localTime(6, 0)),
            agendaOccurrence("calendar-middle", "중간 캘린더 회의", localTime(9, 0), localTime(9, 30)),
            agendaOccurrence("calendar-first", "가장 이른 캘린더", localTime(7, 30), localTime(8, 0)),
          ],
          instrumentation: {
            repositoryGroupCount: 2,
            repositoryGroups: ["events", "scheduleBlocks"],
            occurrenceCount: 3,
            rangeDays: 7,
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
      body: JSON.stringify({ success: true, data: [] }),
    });
  });

  await page.goto("/schedule");
  await page.getByTestId("schedule-view-option-week").click();
  await expect(page.locator(".week-stack-board")).toBeVisible({ timeout: 30_000 });

  const mondayColumn = page.locator(".week-stack-day").filter({ hasText: "월요일" });
  const orderedItems = await mondayColumn.getByTestId("week-stack-item").evaluateAll((items) =>
    items.map((item) => item.textContent ?? ""),
  );

  expect(orderedItems.join("\n")).toMatch(
    /이어지는 밤샘 점검[\s\S]*07:30[\s\S]*가장 이른 캘린더[\s\S]*08:00[\s\S]*이른 루틴 업무[\s\S]*09:00[\s\S]*중간 캘린더 회의[\s\S]*10:00[\s\S]*늦은 루틴 업무/,
  );
});

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
            agendaOccurrence("offset-early", "오프셋 이른 회의", "2026-01-02T08:10:00+09:00", "2026-01-02T08:40:00+09:00"),
            agendaOccurrence("unscheduled-idea", "날짜 미정 아이디어", null, null, "TASK"),
          ],
          instrumentation: {
            repositoryGroupCount: 3,
            repositoryGroups: ["events", "tasks", "routineBlocks"],
            occurrenceCount: 6,
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
  await expect(groups.nth(0)).toContainText("오프셋 이른 회의");
  await expect(groups.nth(0)).toContainText("오전 제품 리뷰");
  await expect(groups.nth(0)).toContainText("밤샘 출시 점검");
  await expect(groups.nth(1)).toContainText("밤샘 출시 점검");
  await expect(groups.nth(1)).toContainText("다음날 성장 과제");
  await expect(groups.nth(2)).toContainText("날짜 미정");
  await expect(groups.nth(2)).toContainText("날짜 미정 아이디어");
  await expect(groups.nth(2).locator(".agenda-day-head")).toBeDisabled();
  await expect(page.getByTestId("agenda-occurrence")).toHaveCount(7);

  const orderedTitles = await page.getByTestId("agenda-occurrence").evaluateAll((items) =>
    items.map((item) => item.textContent ?? ""),
  );
  expect(orderedTitles.join("\n")).toMatch(/아침 루틴 정리[\s\S]*오프셋 이른 회의[\s\S]*오전 제품 리뷰[\s\S]*밤샘 출시 점검[\s\S]*밤샘 출시 점검[\s\S]*다음날 성장 과제/);

  await groups.nth(0).locator(".agenda-day-head").click();
  await expect(page.getByTestId("selected-day-timeline")).toBeVisible();
  await expect(page.getByTestId("schedule-view-option-day")).toHaveAttribute("aria-pressed", "true");
  await expect(page.getByTestId("selected-day-occurrence")).toContainText(["아침 루틴 정리", "오프셋 이른 회의", "오전 제품 리뷰", "밤샘 출시 점검"]);
});

test("agenda and selected day occurrence cards keep long text in the full card width", async ({
  page,
}, testInfo) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await loginAsUniqueMockUser(page, testInfo);
  await completeOnboardingIfPresent(page);

  const longTitle = "오늘 내일 연차와 출근 조정 때문에 매우 긴 이름으로 들어온 고객사 프로젝트 킥오프 회의";

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
            agendaOccurrence("long-agenda-event", longTitle, "2026-01-02T00:20:00.000Z", "2026-01-02T09:40:00.000Z"),
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

  await page.goto("/schedule");
  await page.getByTestId("schedule-view-option-agenda").click();
  await expect(page.getByTestId("agenda-stream")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("agenda-occurrence").first()).toContainText(longTitle);

  await expectOccurrenceCardsUseFullWidth(page, "agenda-occurrence");

  await page.getByTestId("agenda-day-group").first().locator(".agenda-day-head").click();
  await expect(page.getByTestId("selected-day-timeline")).toBeVisible();
  await expect(page.getByTestId("selected-day-occurrence").first()).toContainText(longTitle);

  await expectOccurrenceCardsUseFullWidth(page, "selected-day-occurrence");
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
  draftStart.setHours(12, 0, 0, 0);
  const draftEnd = new Date(draftStart.getTime() + 30 * 60 * 1000);
  const earlyOccurrenceStart = new Date(draftStart.getTime() - 30 * 60 * 1000);
  const earlyOccurrenceEnd = new Date(draftStart.getTime() - 15 * 60 * 1000);
  const lateOccurrenceStart = new Date(draftStart.getTime() + 60 * 60 * 1000);
  const lateOccurrenceEnd = new Date(draftStart.getTime() + 90 * 60 * 1000);

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
          occurrences: [
            agendaOccurrence("existing-early", "기존 이른 일정", earlyOccurrenceStart.toISOString(), earlyOccurrenceEnd.toISOString()),
            agendaOccurrence("existing-late", "기존 늦은 일정", lateOccurrenceStart.toISOString(), lateOccurrenceEnd.toISOString()),
          ],
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
              explanation: "일정표에서 먼저 확인합니다.",
              commands: [
                {
                  actionType: "create_event",
                  targetType: "event",
                  targetId: null,
                  payload: {
                    title: "확인할 집중 시간",
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
                title: "확인할 집중 시간",
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
  await expect(page.getByTestId("ai-draft-projection")).toContainText("확인할 집중 시간");

  await page.getByTestId("schedule-view-option-month").click();
  await expect(page.getByTestId("monthly-draft-badge").first()).toContainText("확인할 집중 시간", { timeout: 30_000 });

  await page.getByTestId("schedule-view-option-agenda").click();
  await expect(page.getByTestId("agenda-draft-occurrence")).toContainText("확인할 집중 시간", { timeout: 30_000 });

  await page.getByTestId("schedule-view-option-day").click();
  await expect(page.getByTestId("selected-day-draft-occurrence")).toBeVisible({ timeout: 30_000 });
  await expect(page.getByTestId("selected-day-draft-occurrence")).toContainText("확인할 변경 · 반영 전");
  const selectedDayItems = await page.locator(".selected-day-occurrence-list .selected-day-occurrence").evaluateAll((items) =>
    items.map((item) => item.textContent ?? ""),
  );
  expect(selectedDayItems.join("\n")).toMatch(/기존 이른 일정[\s\S]*확인할 집중 시간[\s\S]*기존 늦은 일정/);
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

  const editDialog = page.getByRole("dialog", { name: /일정 수정/ });
  await expect(editDialog).toBeVisible();
  await editDialog.getByLabel("제목").fill(editedTitle);
  await editDialog.getByRole("button", { name: "저장" }).click();

  await expect(editDialog).toHaveCount(0, { timeout: 15_000 });
  expect(patchBody?.title).toBe(editedTitle);
  expect(patchBody?.startAt).toBe("2026-01-02T01:00:00.000Z");
  expect(patchBody?.endAt).toBe("2026-01-02T02:00:00.000Z");
  expect(patchBody?.goalId).toBe("11111111-1111-1111-1111-111111111111");
});

async function expectOccurrenceCardsUseFullWidth(page: import("@playwright/test").Page, testId: string) {
  await expect
    .poll(async () =>
      page.getByTestId(testId).evaluateAll((items) =>
        items.map((item) => {
          const trigger = item.querySelector<HTMLElement>(".occurrence-edit-trigger");
          const timeColumn = trigger?.querySelector<HTMLElement>(".agenda-occurrence-time");
          const copyColumn = trigger?.querySelector<HTMLElement>("div");
          const itemRect = item.getBoundingClientRect();
          const triggerRect = trigger?.getBoundingClientRect();
          const timeRect = timeColumn?.getBoundingClientRect();
          const copyRect = copyColumn?.getBoundingClientRect();
          const triggerStyle = trigger ? window.getComputedStyle(trigger) : null;
          return {
            triggerUsesCardWidth: Boolean(triggerRect && triggerRect.width >= itemRect.width - 2),
            triggerCoversCardEdges: Boolean(
              triggerRect &&
                Math.abs(triggerRect.left - itemRect.left) <= 1 &&
                Math.abs(triggerRect.right - itemRect.right) <= 1,
            ),
            triggerOwnsCardPadding: triggerStyle?.paddingTop === "12px" && triggerStyle?.paddingLeft === "12px",
            timeColumnIsNotCramped: Boolean(timeRect && timeRect.width >= 80),
            copyHasBreathingRoom: Boolean(copyRect && copyRect.width >= Math.min(180, itemRect.width * 0.48)),
            itemWidth: Math.round(itemRect.width),
            triggerWidth: Math.round(triggerRect?.width ?? 0),
            timeWidth: Math.round(timeRect?.width ?? 0),
            copyWidth: Math.round(copyRect?.width ?? 0),
          };
        }),
      ),
    )
    .toEqual([
      expect.objectContaining({
        triggerUsesCardWidth: true,
        triggerCoversCardEdges: true,
        triggerOwnsCardPadding: true,
        timeColumnIsNotCramped: true,
        copyHasBreathingRoom: true,
      }),
    ]);
}
