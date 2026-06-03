import { expect, Page, test } from "@playwright/test";
import fs from "node:fs";
import path from "node:path";

import {
  clearAllScheduleBlocks,
  completeOnboardingIfPresent,
  createScheduleBlockViaApi,
  getCurrentBackendDay,
  loginAsUniqueMockUser,
} from "./helpers";

const ARTIFACT_DIR = process.env.OMX_VISUAL_ARTIFACT_DIR ?? path.resolve(__dirname, "../../.omx/artifacts/visual-ralph-task6");
const VIEWPORTS = [
  { name: "desktop-1440", width: 1440, height: 1000 },
  { name: "narrow-390", width: 390, height: 900 },
] as const;
const MODES = ["week", "month", "day", "agenda"] as const;
const DAY_ORDER = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"] as const;

type Capture = Record<string, unknown>;
const captures: Capture[] = [];
const differences: Array<Record<string, unknown>> = [];

function nextDay(day: string, offset: number) {
  const index = DAY_ORDER.indexOf(day as (typeof DAY_ORDER)[number]);
  return DAY_ORDER[(Math.max(index, 0) + offset) % DAY_ORDER.length];
}

async function seedSchedule(page: Page) {
  const currentDay = getCurrentBackendDay();
  await clearAllScheduleBlocks(page);
  await createScheduleBlockViaApi(page, {
    dayOfWeek: currentDay,
    startTime: "09:00",
    endTime: "10:15",
    activity: "오늘 핵심 업무 브리핑 확인",
    category: "WORK",
    note: "dashboard/schedule width reference seed",
  });
  await createScheduleBlockViaApi(page, {
    dayOfWeek: currentDay,
    startTime: "13:10",
    endTime: "14:00",
    activity: "매우긴한글일정명으로월간일간목록줄바꿈과오버플로우를검증하는QA블록",
    category: "WORK",
    note: "공백없이길게이어지는메모텍스트가카드밖으로삐져나오지않아야합니다".repeat(2),
  });
  await createScheduleBlockViaApi(page, {
    dayOfWeek: nextDay(currentDay, 1),
    startTime: "16:10",
    endTime: "16:40",
    activity: "다음날 회의 준비",
    category: "MEETING",
    note: "agenda/day switch seed",
  });
  await createScheduleBlockViaApi(page, {
    dayOfWeek: nextDay(currentDay, 2),
    startTime: "22:30",
    endTime: "23:20",
    activity: "늦은 시간 루틴 점검",
    category: "ROUTINE",
    note: "timezone edge visual seed",
  });
}

async function waitForMode(page: Page, mode: string) {
  if (mode === "week") await expect(page.locator(".week-stack-board")).toBeVisible({ timeout: 30_000 });
  if (mode === "month") await expect(page.getByTestId("monthly-mosaic")).toBeVisible({ timeout: 30_000 });
  if (mode === "day") await expect(page.getByTestId("selected-day-timeline")).toBeVisible({ timeout: 30_000 });
  if (mode === "agenda") await expect(page.getByTestId("agenda-stream")).toBeVisible({ timeout: 30_000 });
  await page.waitForTimeout(500);
}

async function capture(page: Page, surface: string, viewportName: string) {
  fs.mkdirSync(ARTIFACT_DIR, { recursive: true });
  const screenshotPath = path.join(ARTIFACT_DIR, `${surface}-${viewportName}.png`);
  await page.screenshot({ path: screenshotPath, fullPage: true });
  const metrics = await page.evaluate(() => {
    const selectors = [
      ".app-frame",
      ".app-shell",
      ".app-main",
      ".today-briefing-shell",
      ".today-flow-card",
      ".today-schedule-card",
      ".schedule-layout",
      ".schedule-main-board",
      ".schedule-view-toolbar",
      ".schedule-device-layout",
      ".schedule-calendar-panel",
      ".week-stack-board",
      ".monthly-mosaic-card",
      ".selected-day-card",
      ".agenda-stream-card",
      ".app-right-rail",
      ".schedule-ai-right-rail",
    ];
    function rect(selector: string) {
      const element = document.querySelector<HTMLElement>(selector);
      if (!element) return null;
      const box = element.getBoundingClientRect();
      const style = window.getComputedStyle(element);
      return {
        x: Math.round(box.x),
        y: Math.round(box.y),
        width: Math.round(box.width),
        height: Math.round(box.height),
        display: style.display,
        gridTemplateColumns: style.gridTemplateColumns,
        overflowX: style.overflowX,
        maxWidth: style.maxWidth,
      };
    }
    const viewportWidth = document.documentElement.clientWidth;
    const visibleOverflow = Array.from(document.querySelectorAll<HTMLElement>("body *"))
      .filter((element) => {
        const style = window.getComputedStyle(element);
        const box = element.getBoundingClientRect();
        return style.display !== "none" && style.visibility !== "hidden" && box.width > 0 && box.height > 0 && box.bottom >= 0 && box.top <= window.innerHeight;
      })
      .filter((element) => {
        const box = element.getBoundingClientRect();
        return box.left < -1 || box.right > viewportWidth + 1 || element.scrollWidth > element.clientWidth + 2;
      })
      .slice(0, 12)
      .map((element) => {
        const box = element.getBoundingClientRect();
        return {
          tag: element.tagName.toLowerCase(),
          className: String(element.className || "").slice(0, 160),
          testId: element.getAttribute("data-testid"),
          text: (element.innerText || element.textContent || "").replace(/\s+/g, " ").slice(0, 120),
          rect: { x: Math.round(box.x), width: Math.round(box.width), right: Math.round(box.right) },
          scrollWidth: element.scrollWidth,
          clientWidth: element.clientWidth,
        };
      });
    const active = document.querySelector<HTMLElement>("[data-active-view]")?.dataset.activeView ?? null;
    return {
      url: window.location.href,
      viewport: { width: viewportWidth, height: window.innerHeight },
      document: {
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
        horizontalOverflow: document.documentElement.scrollWidth > document.documentElement.clientWidth + 1,
      },
      activeView: active,
      rects: Object.fromEntries(selectors.map((selector) => [selector, rect(selector)])),
      visibleOverflow,
      activeSwitcherLabels: Array.from(document.querySelectorAll<HTMLElement>(".schedule-view-option.active")).map((el) => el.innerText.replace(/\s+/g, " ")),
    };
  });
  captures.push({ surface, viewportName, screenshotPath, metrics });
  const m = metrics as { document: { horizontalOverflow: boolean }; visibleOverflow: unknown[]; rects: Record<string, { width?: number; x?: number } | null> };
  if (m.document.horizontalOverflow || m.visibleOverflow.length) {
    differences.push({ surface, viewportName, type: "overflow", evidence: { horizontalOverflow: m.document.horizontalOverflow, visibleOverflow: m.visibleOverflow } });
  }
}

test("worker-2 visual ralph dashboard/schedule mode audit", async ({ page }, testInfo) => {
  test.setTimeout(180_000);
  await loginAsUniqueMockUser(page, testInfo, { connectGoogle: false, writeCapable: false });
  await completeOnboardingIfPresent(page);
  await seedSchedule(page);

  for (const viewport of VIEWPORTS) {
    await page.setViewportSize({ width: viewport.width, height: viewport.height });
    await page.goto("/dashboard");
    await expect(page.getByTestId("dashboard-primary-action")).toBeVisible({ timeout: 30_000 });
    await capture(page, "dashboard", viewport.name);
  }

  await page.goto("/schedule");
  await expect(page.getByTestId("schedule-add-button").or(page.getByRole("button", { name: "일정 직접 추가" })).first()).toBeVisible({ timeout: 30_000 });

  for (const mode of MODES) {
    for (const viewport of VIEWPORTS) {
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.getByTestId(`schedule-view-option-${mode}`).click();
      await waitForMode(page, mode);
      await capture(page, `schedule-${mode}`, viewport.name);
    }
  }

  const byKey = new Map(captures.map((entry) => [`${entry.surface}:${entry.viewportName}`, entry]));
  const dashboardDesktop = byKey.get("dashboard:desktop-1440") as { metrics?: { rects?: Record<string, { width?: number } | null> } } | undefined;
  const weekDesktop = byKey.get("schedule-week:desktop-1440") as { metrics?: { rects?: Record<string, { width?: number } | null> } } | undefined;
  const dashboardWidth = dashboardDesktop?.metrics?.rects?.[".today-briefing-shell"]?.width ?? null;
  const scheduleWidth = weekDesktop?.metrics?.rects?.[".schedule-main-board"]?.width ?? null;
  if (dashboardWidth && scheduleWidth && Math.abs(dashboardWidth - scheduleWidth) > 24) {
    differences.push({
      surface: "dashboard-vs-schedule-week",
      viewportName: "desktop-1440",
      type: "width-mismatch",
      evidence: { dashboardTodayBriefingShellWidth: dashboardWidth, scheduleMainBoardWidth: scheduleWidth, deltaPx: Math.round(Math.abs(dashboardWidth - scheduleWidth)) },
    });
  }

  const artifact = {
    generated_at: new Date().toISOString(),
    task_id: "6",
    baseline_reference: "weekly schedule planner / .schedule-main-board is approved width reference",
    screenshots: captures.map((entry) => ({ surface: entry.surface, viewportName: entry.viewportName, path: entry.screenshotPath })),
    captures,
    score: differences.length === 0 ? 0.9 : differences.length <= 2 ? 0.72 : 0.58,
    verdict: differences.length === 0 ? "pass-with-watchpoints" : "needs-fix",
    category_match: differences.length === 0 ? "weekly reference broadly matched in captured states" : "visual/layout mismatches found against weekly reference and no-overflow contract",
    differences,
    suggestions: [
      {
        file: "frontend/app/globals.css",
        recommendation: "Keep dashboard .today-briefing-shell and schedule .schedule-main-board on the same width token/gutter behavior; verify at >=1320px and <=520px.",
      },
      {
        file: "frontend/components/schedule-view.tsx",
        recommendation: "Mode switch states should preserve toolbar and calendar panel geometry across month/week/day/agenda and avoid scroll/overflow jumps after switching.",
      },
      {
        file: "frontend/e2e/visual-qa.spec.ts",
        recommendation: "Extend visual QA to include explicit month/day/agenda mode screenshots, not only the default weekly schedule surface.",
      },
    ],
    reasoning: "Visual-Ralph audit captured dashboard plus schedule month/week/day/agenda at desktop and narrow widths using isolated mock-login E2E data. Differences are derived from screenshot geometry, horizontal overflow checks, and the weekly schedule planner width as the reference authority.",
  };
  fs.writeFileSync(path.join(ARTIFACT_DIR, "visual-ralph-task6-verdict.json"), JSON.stringify(artifact, null, 2), "utf8");
});
