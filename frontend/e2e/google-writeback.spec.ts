import { expect, test } from "@playwright/test";

import { backendFetch, completeOnboardingIfPresent, loginAsUniqueMockUser } from "./helpers";

const BANNED_USER_COPY =
  /Google 연결|Google 계정 연결됨|Google 읽기|Google 반영 대기|마지막 동기화|연결 상태 확인|권한 상태|확인한 내용/;

interface ApiEnvelope<T> {
  data: T;
  meta?: Record<string, unknown>;
}

interface EventResponse {
  id: string;
  title: string;
  syncState: string;
  externalSourceId: string | null;
}

interface EventListResponse {
  data: EventResponse[];
}

test("mocked Google write-back flush updates provider state without dashboard sync clutter", async ({ page }, testInfo) => {
  await loginAsUniqueMockUser(page, testInfo, { connectGoogle: true, writeCapable: true });
  await completeOnboardingIfPresent(page);

  const statusBefore = await backendFetch<ApiEnvelope<unknown>>(page, "/api/sync/status");
  expect(statusBefore.meta?.externalWriteEnabled).toBe(true);
  expect(statusBefore.meta?.capabilityStatus).toBe("write_enabled");

  const inboundCalendar = await backendFetch<ApiEnvelope<{ affectedCount: number }>>(
    page,
    "/api/sync/google/calendar",
    {
      method: "POST",
      body: { mode: "inbound", resolvePolicy: "proposal_first" },
    },
  );
  expect(inboundCalendar.data.affectedCount).toBeGreaterThanOrEqual(1);

  const inboundTasks = await backendFetch<ApiEnvelope<{ affectedCount: number }>>(
    page,
    "/api/sync/google/tasks",
    {
      method: "POST",
      body: { mode: "inbound", resolvePolicy: "proposal_first" },
    },
  );
  expect(inboundTasks.data.affectedCount).toBeGreaterThanOrEqual(1);

  const startAt = new Date(Date.now() + 6 * 60 * 60 * 1000);
  const endAt = new Date(startAt.getTime() + 30 * 60 * 1000);
  const created = await backendFetch<ApiEnvelope<EventResponse>>(page, "/api/events", {
    method: "POST",
    body: {
      title: `Mock Google write-back ${Date.now()}`,
      description: "Playwright mocked provider write-back seed",
      startAt: startAt.toISOString(),
      endAt: endAt.toISOString(),
      priority: 3,
      category: "WORK",
      goalId: null,
    },
  });
  expect(created.data.syncState).toBe("DIRTY_PENDING_WRITE");

  const pendingStatus = await backendFetch<ApiEnvelope<unknown>>(page, "/api/sync/status");
  expect(pendingStatus.meta?.pendingProviderWriteCount).toBeGreaterThanOrEqual(1);

  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: /오늘 일정은|오늘 예정된 일정이 없습니다/ }).first()).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
  await page.screenshot({
    path: testInfo.outputPath("google-writeback-pending-dashboard-silent.png"),
    fullPage: true,
  });

  const outbound = await backendFetch<ApiEnvelope<{ affectedCount: number; status: string }>>(
    page,
    "/api/sync/google/calendar",
    {
      method: "POST",
      body: { mode: "outbound", resolvePolicy: "proposal_first" },
    },
  );
  expect(outbound.data.status).toBe("success");
  expect(outbound.data.affectedCount).toBeGreaterThanOrEqual(1);

  const from = new Date(startAt.getTime() - 60 * 60 * 1000).toISOString();
  const to = new Date(endAt.getTime() + 60 * 60 * 1000).toISOString();
  const events = await backendFetch<EventListResponse>(page, `/api/events?from=${from}&to=${to}`);
  const writtenEvent = events.data.find((event) => event.id === created.data.id);
  expect(writtenEvent?.syncState).toBe("SYNCED");
  expect(writtenEvent?.externalSourceId).toMatch(/^google_calendar:mock-calendar-/);

  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: /오늘 일정은|오늘 예정된 일정이 없습니다/ }).first()).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
  await page.screenshot({
    path: testInfo.outputPath("google-writeback-ready-dashboard-silent.png"),
    fullPage: true,
  });
});
