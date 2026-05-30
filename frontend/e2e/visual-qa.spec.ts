import { expect, Locator, Page, TestInfo, test } from "@playwright/test";

import {
  backendFetch,
  buildActiveScheduleBlock,
  clearScheduleBlocksForDay,
  completeOnboardingIfPresent,
  createScheduleBlockViaApi,
  escapeRegExp,
  getCurrentBackendDay,
  loginAsUniqueMockUser,
} from "./helpers";

const BANNED_USER_COPY =
  /Google 연결|Google 계정 연결됨|Google 읽기|Google 반영 대기|마지막 동기화|연결 상태 확인|근거|기준으로 재배치|AI 비서 메모|예상 영향|확인한 내용|권한 상태|조정안 핵심 요약|추천 집중|실제 집중 상태|접어|confidence|stage|matchEvidence|validationTrace|repairAttempt|chainOfThought|reasoning|reason|missingFields|ambiguousFields/;

const VIEWPORTS = [
  { name: "desktop-1440", width: 1440, height: 1000 },
  { name: "tablet-768", width: 768, height: 1024 },
  { name: "mobile-375", width: 375, height: 812 },
] as const;

interface WeekScheduleResponse {
  week: Array<{
    dayOfWeek: string;
    blocks: Array<{
      activity: string;
      startTime: string;
      endTime: string;
      category: string;
    }>;
  }>;
}

function currentKoreaMinutes() {
  const parts = Object.fromEntries(
    new Intl.DateTimeFormat("en-US", {
      hour: "2-digit",
      minute: "2-digit",
      hourCycle: "h23",
      timeZone: "Asia/Seoul",
    })
      .formatToParts(new Date())
      .map((part) => [part.type, part.value]),
  );

  return Number(parts.hour ?? "0") * 60 + Number(parts.minute ?? "0");
}

function minutesFromClock(value: string) {
  const [hour = "0", minute = "0"] = value.split(":");
  return Number(hour) * 60 + Number(minute);
}

function isActiveNow(block: { startTime: string; endTime: string }, currentMinutes: number) {
  const start = minutesFromClock(block.startTime);
  const endClock = minutesFromClock(block.endTime);
  const end = endClock > start ? endClock : endClock + 24 * 60;
  const normalizedNow = currentMinutes < start && end > 24 * 60 ? currentMinutes + 24 * 60 : currentMinutes;

  return normalizedNow >= start && normalizedNow < end;
}

function preferredAction(
  page: Page,
  testId: string,
  fallbackName: string | RegExp,
): Locator {
  return page
    .getByTestId(testId)
    .or(page.getByRole("button", { name: fallbackName }))
    .or(page.getByRole("link", { name: fallbackName }))
    .first();
}

async function assertNoHorizontalOverflow(page: Page) {
  await expect
    .poll(async () =>
      page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth),
    )
    .toBe(true);
}

async function captureResponsiveSurface(
  page: Page,
  testInfo: TestInfo,
  surface: string,
  assertPrimaryVisible: () => Promise<void>,
) {
  for (const { name, width, height } of VIEWPORTS) {
    await page.setViewportSize({ width, height });
    await assertPrimaryVisible();
    await assertNoHorizontalOverflow(page);
    await page.screenshot({ path: testInfo.outputPath(`${surface}-${name}.png`), fullPage: true });
  }
}

test("captures core local visual QA surfaces", async ({ page }, testInfo) => {
  await page.goto("/auth/callback?status=error&reason=e2e");
  await expect(page.getByRole("heading", { name: /로그인을 다시 시도해 주세요/ })).toBeVisible({ timeout: 30_000 });
  await captureResponsiveSurface(page, testInfo, "auth-callback", async () => {
    await expect(page.getByRole("link", { name: /로그인 화면으로 돌아가기/ })).toBeVisible();
  });

  await page.goto("/login");
  await expect(page.getByRole("button", { name: /Google로 시작|로그인 준비 중/ })).toBeVisible({
    timeout: 30_000,
  });
  await captureResponsiveSurface(page, testInfo, "login", async () => {
    await expect(page.getByRole("button", { name: /Google로 시작|로그인 준비 중/ })).toBeVisible();
  });

  await loginAsUniqueMockUser(page, testInfo, { connectGoogle: false, writeCapable: false });
  if (new URL(page.url()).pathname.includes("/onboarding")) {
    const onboardingPrimary = preferredAction(
      page,
      "onboarding-continue-button",
      /저장하고 계속|둘러보기|적용하고 시작/,
    );
    await expect(onboardingPrimary).toBeVisible({ timeout: 45_000 });
    await captureResponsiveSurface(page, testInfo, "onboarding", async () => {
      await expect(onboardingPrimary).toBeVisible();
    });
  }

  await completeOnboardingIfPresent(page);
  const currentDay = getCurrentBackendDay();
  const currentMinutes = currentKoreaMinutes();
  const week = await backendFetch<WeekScheduleResponse>(page, "/api/schedule/week");
  const activeBlock = week.week
    .find((day) => day.dayOfWeek === currentDay)
    ?.blocks.find((block) => block.category !== "SLEEP" && isActiveNow(block, currentMinutes));
  const activeScheduleTitle = activeBlock?.activity ?? "근무 (현재 일정 QA)";

  if (!activeBlock) {
    await clearScheduleBlocksForDay(page, currentDay);
    await createScheduleBlockViaApi(page, buildActiveScheduleBlock(activeScheduleTitle));
  }

  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: /오늘 일정은|오늘 예정된 일정이 없습니다/ }).first()).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
  await captureResponsiveSurface(page, testInfo, "dashboard", async () => {
    const dashboardPrimary = preferredAction(page, "dashboard-primary-action", /주간 일정 보기|오늘 일정 보기|일정 보러가기|다시 불러오기/);
    await expect(dashboardPrimary).toBeVisible();
  });

  await page.goto("/schedule");
  await page.setViewportSize({ width: 1440, height: 1000 });
  const scheduleAddButton = preferredAction(page, "schedule-add-button", "일정 직접 추가");
  await expect(scheduleAddButton).toBeVisible({ timeout: 30_000 });
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
  await expect(page.getByRole("button", { name: new RegExp(escapeRegExp(activeScheduleTitle)) }).first()).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.locator(".current-schedule-card").filter({ hasText: activeScheduleTitle })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.locator(".current-schedule-card").getByText("지금 일정")).toBeVisible({ timeout: 30_000 });
  await expect
    .poll(async () =>
      page.locator(".week-grid-scroll").evaluate((element) => element.clientHeight >= element.scrollHeight - 1),
    )
    .toBe(true);
  await expect
    .poll(async () =>
      page
        .getByRole("button", { name: new RegExp(escapeRegExp(activeScheduleTitle)) })
        .first()
        .evaluate((block) => (block as HTMLElement).offsetTop),
    )
    .toBeLessThan(760);
  await captureResponsiveSurface(page, testInfo, "schedule", async () => {
    await expect(scheduleAddButton).toBeVisible();
  });

  await scheduleAddButton.click();
  const createDialog = page.getByRole("dialog", { name: /새 블록 추가/ });
  await expect(createDialog).toBeVisible({ timeout: 15_000 });
  await captureResponsiveSurface(page, testInfo, "schedule-modal", async () => {
    await expect(createDialog.getByRole("button", { name: "블록 추가" })).toBeVisible();
    await expect(createDialog.getByRole("button", { name: "취소" })).toBeVisible();
  });
  await createDialog.getByRole("button", { name: "취소" }).click();

  await page.goto("/focus");
  await expect(page.getByText(/지금 실행|지금 할 일/).first()).toBeVisible({ timeout: 30_000 });
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
  await captureResponsiveSurface(page, testInfo, "focus", async () => {
    const focusPrimary = preferredAction(page, "focus-primary-action", /완료|삭제|일정 보기|오늘 일정 보기/);
    await expect(focusPrimary).toBeVisible();
  });
});
