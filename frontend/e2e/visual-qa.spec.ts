import { expect, test } from "@playwright/test";

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
  /Google 연결|Google 계정 연결됨|Google 읽기|Google 반영 대기|마지막 동기화|연결 상태 확인|근거|기준으로 재배치|AI 비서 메모|예상 영향|확인한 내용|권한 상태|조정안 핵심 요약|추천 집중|실제 집중 상태|접어/;

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

test("captures core local visual QA surfaces", async ({ page }, testInfo) => {
  await page.goto("/login");
  await expect(page.getByRole("button", { name: /Google로 시작|로그인 준비 중/ })).toBeVisible({
    timeout: 30_000,
  });
  await page.screenshot({ path: testInfo.outputPath("login.png"), fullPage: true });

  await loginAsUniqueMockUser(page, testInfo, { connectGoogle: true, writeCapable: false });
  if (new URL(page.url()).pathname.includes("/onboarding")) {
    await expect(
      page.getByRole("button", { name: /저장하고 계속|둘러보기|적용하고 시작/ }).first(),
    ).toBeVisible({ timeout: 45_000 });
    await page.screenshot({ path: testInfo.outputPath("onboarding.png"), fullPage: true });
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
  await page.screenshot({ path: testInfo.outputPath("dashboard-today.png"), fullPage: true });

  await page.goto("/schedule");
  await expect(page.getByRole("button", { name: "일정 직접 추가" })).toBeVisible({ timeout: 30_000 });
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
  await page.screenshot({ path: testInfo.outputPath("weekly-schedule.png"), fullPage: true });

  await page.goto("/focus");
  await expect(page.getByText(/지금 실행|지금 할 일/).first()).toBeVisible({ timeout: 30_000 });
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
  await page.screenshot({ path: testInfo.outputPath("focus-mode.png"), fullPage: true });
});
