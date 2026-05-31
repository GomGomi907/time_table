import { expect, Locator, Page, TestInfo, test } from "@playwright/test";

import {
  assertNoHorizontalOverflow,
  assertNoInternalUserCopy,
  assertSelectorTextDoesNotOverflow,
  assertSingleLineBySelector,
  assertSingleVisibleByTestId,
  backendFetch,
  buildActiveScheduleBlock,
  clearScheduleBlocksForDay,
  completeOnboardingIfPresent,
  createScheduleBlockViaApi,
  escapeRegExp,
  getCurrentBackendDay,
  loginAsUniqueMockUser,
} from "./helpers";

const VIEWPORTS = [
  { name: "desktop-1440", width: 1440, height: 1000 },
  { name: "desktop-1280", width: 1280, height: 1000 },
  { name: "tablet-boundary-1180", width: 1180, height: 1000 },
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

async function assertReleaseVisualDiscipline(page: Page) {
  await assertNoHorizontalOverflow(page);
  await assertNoInternalUserCopy(page);

  await expect
    .poll(async () =>
      page.evaluate(() => {
        const viewportWidth = document.documentElement.clientWidth;
        const interactiveElements = Array.from(
          document.querySelectorAll<HTMLElement>("button, a, input, textarea, select, [role='button']"),
        );

        return interactiveElements
          .filter((element) => {
            const style = window.getComputedStyle(element);
            const rect = element.getBoundingClientRect();
            return style.visibility !== "hidden" && style.display !== "none" && rect.width > 0 && rect.height > 0;
          })
          .every((element) => {
            const rect = element.getBoundingClientRect();
            return rect.left >= -1 && rect.right <= viewportWidth + 1;
          });
      }),
    )
    .toBe(true);
}

async function assertScheduleRightRailContract(page: Page) {
  const appRail = await assertSingleVisibleByTestId(page, "app-right-rail");
  const scheduleRail = await assertSingleVisibleByTestId(page, "schedule-ai-right-rail");
  await assertSingleVisibleByTestId(page, "schedule-ai-request-input");
  await assertSingleVisibleByTestId(page, "schedule-ai-request-submit");
  await assertSingleLineBySelector(page, "[data-testid='schedule-pending-count']");

  const viewport = page.viewportSize();
  const board = page.locator(".schedule-main-board").first();
  await expect(board).toBeVisible({ timeout: 30_000 });

  if (viewport && viewport.width > 1180) {
    const boardBox = await board.boundingBox();
    const appRailBox = await appRail.boundingBox();
    const scheduleRailBox = await scheduleRail.boundingBox();
    if (!boardBox || !appRailBox || !scheduleRailBox) {
      throw new Error("Schedule board and AI right rail must have visible geometry.");
    }
    expect(appRailBox.x).toBeGreaterThan(boardBox.x + boardBox.width + 12);
    expect(scheduleRailBox.x).toBeGreaterThan(boardBox.x + boardBox.width + 12);
    expect(scheduleRailBox.width).toBeLessThanOrEqual(380);
    return;
  }

  await expect
    .poll(async () => {
      const boardBox = await board.boundingBox();
      const railBox = await scheduleRail.boundingBox();
      return Boolean(boardBox && railBox && railBox.y >= boardBox.y + boardBox.height - 2);
    })
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
    await assertReleaseVisualDiscipline(page);
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
      await assertSingleVisibleByTestId(page, "onboarding-answer-count");
      await assertSingleLineBySelector(page, "[data-testid='onboarding-answer-count']");
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
  await assertNoInternalUserCopy(page);
  await captureResponsiveSurface(page, testInfo, "dashboard", async () => {
    const dashboardPrimary = preferredAction(page, "dashboard-primary-action", /주간 일정 보기|오늘 일정 보기|일정 보러가기|다시 불러오기/);
    await expect(dashboardPrimary).toBeVisible();
  });

  await page.goto("/schedule");
  await page.setViewportSize({ width: 1440, height: 1000 });
  const scheduleAddButton = preferredAction(page, "schedule-add-button", "일정 직접 추가");
  await expect(scheduleAddButton).toBeVisible({ timeout: 30_000 });
  await assertNoInternalUserCopy(page);
  await assertScheduleRightRailContract(page);
  await expect(page.getByRole("button", { name: new RegExp(escapeRegExp(activeScheduleTitle)) }).first()).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.locator(".current-schedule-card").filter({ hasText: activeScheduleTitle })).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.locator(".current-schedule-card").getByText("지금 일정")).toBeVisible({ timeout: 30_000 });
  await expect(page.locator(".week-stack-board")).toBeVisible({ timeout: 30_000 });
  await assertSelectorTextDoesNotOverflow(page, ".week-stack-board");
  await expect
    .poll(async () =>
      page
        .getByRole("button", { name: new RegExp(escapeRegExp(activeScheduleTitle)) })
        .first()
        .evaluate((block) => (block as HTMLElement).offsetTop),
    )
    .toBeLessThan(760);
  await page.setViewportSize({ width: 960, height: 1000 });
  await expect(page.locator(".week-stack-board")).toBeVisible({ timeout: 30_000 });
  await assertNoHorizontalOverflow(page);
  await assertSelectorTextDoesNotOverflow(page, ".week-stack-board");
  await captureResponsiveSurface(page, testInfo, "schedule", async () => {
    await expect(scheduleAddButton).toBeVisible();
    await assertScheduleRightRailContract(page);
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
  await assertNoInternalUserCopy(page);
  await captureResponsiveSurface(page, testInfo, "focus", async () => {
    const focusPrimary = preferredAction(page, "focus-primary-action", /완료|삭제|일정 보기|오늘 일정 보기/);
    await expect(focusPrimary).toBeVisible();
  });
});
