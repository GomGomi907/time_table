import { expect, Page, TestInfo } from "@playwright/test";

const API_BASE_URL = process.env.PLAYWRIGHT_API_URL ?? "http://localhost:8080";
const KOREA_TIME_ZONE = "Asia/Seoul";
export const BANNED_USER_COPY =
  /Google 연결|Google 계정 연결됨|Google 읽기|Google 반영 대기|마지막 동기화|연결 상태 확인|근거|기준으로 재배치|AI 비서 메모|예상 영향|확인한 내용|권한 상태|조정안 핵심 요약|추천 집중|실제 집중 상태|접어|월간 모자이크|아젠다|Selected Day|Range spine|캘린더 range|range 응답|draft|canonical|commandBatch|command|providerMetadata|payload|requestKind|resolutionType|confidence|stage|matchEvidence|validation|validationTrace|repairAttempt|chainOfThought|reasoning|["']reason["']\s*:|missingFields|ambiguousFields|초안|명령|제공자|검증|INTERNAL_REASON_SHOULD_NOT_RENDER/i;

const BACKEND_DAY_BY_WEEKDAY: Record<string, string> = {
  Monday: "MONDAY",
  Tuesday: "TUESDAY",
  Wednesday: "WEDNESDAY",
  Thursday: "THURSDAY",
  Friday: "FRIDAY",
  Saturday: "SATURDAY",
  Sunday: "SUNDAY",
};

const DISPLAY_DAY_BY_BACKEND_DAY: Record<string, string> = Object.fromEntries(
  Object.entries(BACKEND_DAY_BY_WEEKDAY).map(([display, backend]) => [backend, display]),
);
const WEEK_DAY_ORDER = ["MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY"];
const WEEK_MINUTES = WEEK_DAY_ORDER.length * 24 * 60;

interface BrowserFetchInit {
  method?: string;
  headers?: Record<string, string>;
  body?: unknown;
}

interface CsrfResponse {
  headerName: string;
  token: string;
}

export interface ScheduleBlockInput {
  dayOfWeek: string;
  startTime: string;
  endTime: string;
  activity: string;
  category: string;
  note?: string;
}

interface WeekScheduleResponse {
  week: Array<{
    dayOfWeek: string;
    blocks: Array<{
      id: string;
      activity?: string;
      startTime: string;
      endTime: string;
    }>;
  }>;
}

export async function loginThroughStartButton(
  page: Page,
  options: { finalUrl?: RegExp } = {},
) {
  await page.goto("/login");
  await page.waitForLoadState("domcontentloaded");

  const alreadyRouted = await page
    .waitForURL(/\/(onboarding|dashboard)(?:$|\?)/, { timeout: 3_000 })
    .then(() => true)
    .catch(() => false);
  if (alreadyRouted) {
    return;
  }

  const startButton = page.getByRole("button", { name: /Google로 시작|로그인 준비 중/ });
  await expect(startButton).toBeVisible({ timeout: 15_000 });
  await startButton.click();

  await expect(page).toHaveURL(/\/(auth\/callback|onboarding|dashboard)(?:$|\?)/, {
    timeout: 30_000,
  });
  await expect(page).toHaveURL(options.finalUrl ?? /\/(onboarding|dashboard)(?:$|\?)/, {
    timeout: 30_000,
  });
}

export async function loginAsUniqueMockUser(
  page: Page,
  testInfo: TestInfo,
  options: { connectGoogle?: boolean; writeCapable?: boolean } = {},
) {
  const slug = testInfo.title
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-|-$/g, "")
    .slice(0, 34);
  const uniqueId = `${slug || "user"}-${Date.now()}-${testInfo.workerIndex}`;
  const email = `e2e-${uniqueId}@time-table.test`;
  const params = new URLSearchParams({
    email,
    name: `E2E ${uniqueId}`,
    connectGoogle: String(Boolean(options.connectGoogle)),
    writeCapable: String(Boolean(options.writeCapable)),
  });

  await page.goto(`${API_BASE_URL}/api/auth/mock/login?${params.toString()}`);
  await expect(page).toHaveURL(/\/(auth\/callback|onboarding|dashboard)(?:$|\?)/, {
    timeout: 30_000,
  });
  await expect(page).toHaveURL(/\/(onboarding|dashboard)(?:$|\?)/, {
    timeout: 30_000,
  });

  return { email };
}

export async function completeOnboardingIfPresent(page: Page) {
  await expect(page).toHaveURL(/\/(onboarding|dashboard)(?:$|\?)/, {
    timeout: 30_000,
  });

  if (!new URL(page.url()).pathname.includes("/onboarding")) {
    return;
  }

  await expect(
    page.getByRole("button", {
      name: /^(기본값으로 계속|바꾼 시간으로 계속|일정표 보기|건너뛰고 일정표 보기|추천 시간 넣고 일정표 보기|바로 시작하기|둘러보기)$/,
    }).first(),
  ).toBeVisible({ timeout: 45_000 });

  const browseButton = page.getByRole("button", { name: /^(둘러보기|일정표 보기|건너뛰고 일정표 보기)$/ }).first();
  if (await browseButton.isVisible({ timeout: 500 }).catch(() => false)) {
    await browseButton.click();
    await expect(page).toHaveURL(/\/dashboard(?:$|\?)/, { timeout: 30_000 });
    return;
  }

  await clickOptionalButton(page, /^07:00/);
  await clickOptionalButton(page, /^09:00/);
  await clickOptionalButton(page, /^19:00/);
  await clickOptionalButton(page, /^23:30/);
  await clickOptionalButton(page, /균형 있게/);

  const saveButton = page.getByTestId("onboarding-continue-button");
  await expect(saveButton).toBeEnabled({ timeout: 30_000 });
  await saveButton.click();

  await expect(browseButton).toBeVisible({ timeout: 30_000 });
  await browseButton.click();
  await expect(page).toHaveURL(/\/dashboard(?:$|\?)/, { timeout: 30_000 });
}

export async function createScheduleBlockViaApi(page: Page, block: ScheduleBlockInput) {
  await removeOverlappingScheduleBlocks(page, block);
  await backendFetch(page, "/api/schedule/blocks", {
    method: "POST",
    body: block,
  });
}

export function getCurrentBackendDay() {
  const weekday = new Intl.DateTimeFormat("en-US", {
    weekday: "long",
    timeZone: KOREA_TIME_ZONE,
  }).format(new Date());

  return BACKEND_DAY_BY_WEEKDAY[weekday] ?? "MONDAY";
}

export async function clearScheduleBlocksForDay(page: Page, dayOfWeek = getCurrentBackendDay()) {
  const week = await backendFetch<WeekScheduleResponse>(page, "/api/schedule/week");
  const day = week.week.find((item) => dayIndex(item.dayOfWeek) === dayIndex(dayOfWeek));

  for (const existing of day?.blocks ?? []) {
    await backendFetch(page, `/api/schedule/blocks/${existing.id}`, { method: "DELETE" });
  }
}

export async function clearAllScheduleBlocks(page: Page) {
  const week = await backendFetch<WeekScheduleResponse>(page, "/api/schedule/week");

  for (const day of week.week) {
    for (const existing of day.blocks) {
      await backendFetch(page, `/api/schedule/blocks/${existing.id}`, { method: "DELETE" });
    }
  }
}

async function removeOverlappingScheduleBlocks(page: Page, block: ScheduleBlockInput) {
  const week = await backendFetch<WeekScheduleResponse>(page, "/api/schedule/week");
  const targetInterval = toWeekInterval(block.dayOfWeek, block.startTime, block.endTime);

  for (const day of week.week) {
    for (const existing of day.blocks) {
      const existingInterval = toWeekInterval(day.dayOfWeek, existing.startTime, existing.endTime);
      if (weekIntervalsOverlap(targetInterval, existingInterval)) {
        await backendFetch(page, `/api/schedule/blocks/${existing.id}`, { method: "DELETE" });
      }
    }
  }
}

export function buildActiveScheduleBlock(activity: string): ScheduleBlockInput {
  const now = getKoreaNowParts();
  const totalMinutes = now.hour * 60 + now.minute;
  const startMinutes = Math.max(0, totalMinutes - 10);
  const endMinutes = (totalMinutes + 35) % (24 * 60);

  return {
    dayOfWeek: BACKEND_DAY_BY_WEEKDAY[now.weekday] ?? "MONDAY",
    startTime: formatMinutes(startMinutes),
    endTime: formatMinutes(endMinutes),
    activity,
    category: "WORK",
    note: "E2E schedule-only focus seed",
  };
}

export function escapeRegExp(value: string) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

export async function assertNoInternalUserCopy(page: Page) {
  const leakedInternalCopy = await page.evaluate(
    ({ source, flags }) => {
      const bodyClone = document.body.cloneNode(true) as HTMLElement;
      bodyClone
        .querySelectorAll("input, textarea, [data-user-content='true']")
        .forEach((element) => element.remove());

      return (bodyClone.innerText ?? bodyClone.textContent ?? "").match(new RegExp(source, flags))?.[0] ?? null;
    },
    { source: BANNED_USER_COPY.source, flags: BANNED_USER_COPY.flags },
  );

  expect(leakedInternalCopy, `Internal implementation copy leaked to app-authored UI: ${leakedInternalCopy}`).toBeNull();
}

export async function assertNoHorizontalOverflow(page: Page) {
  await expect
    .poll(async () =>
      page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth),
    )
    .toBe(true);
}

export async function assertNoVisibleElementOverflowsViewport(page: Page) {
  await expect
    .poll(async () =>
      page.evaluate(() => {
        const viewportWidth = document.documentElement.clientWidth;
        const ignoredTags = new Set(["HTML", "BODY", "SCRIPT", "STYLE", "NOSCRIPT"]);

        return Array.from(document.querySelectorAll<HTMLElement>("body *"))
          .filter((element) => {
            if (ignoredTags.has(element.tagName)) {
              return false;
            }
            const style = window.getComputedStyle(element);
            const rect = element.getBoundingClientRect();
            return (
              style.display !== "none"
              && style.visibility !== "hidden"
              && rect.width > 0
              && rect.height > 0
              && rect.bottom >= 0
              && rect.top <= window.innerHeight
            );
          })
          .filter((element) => {
            const rect = element.getBoundingClientRect();
            return rect.left < -1 || rect.right > viewportWidth + 1 || element.scrollWidth > element.clientWidth + 1;
          })
          .slice(0, 5)
          .map((element) => {
            const rect = element.getBoundingClientRect();
            const label =
              element.getAttribute("data-testid")
              ?? element.getAttribute("aria-label")
              ?? element.textContent?.trim().slice(0, 80)
              ?? element.tagName.toLowerCase();
            return `${element.tagName.toLowerCase()} ${label} left=${Math.round(rect.left)} right=${Math.round(rect.right)} width=${Math.round(rect.width)} scroll=${element.scrollWidth}/${element.clientWidth}`;
          });
      }),
    )
    .toEqual([]);
}

export async function assertSelectorTextDoesNotOverflow(page: Page, selector: string) {
  await expect
    .poll(async () =>
      page.locator(selector).first().evaluate((element) => element.scrollWidth <= element.clientWidth + 1),
    )
    .toBe(true);
}

export async function assertSingleLineBySelector(page: Page, selector: string) {
  await expect
    .poll(async () =>
      page.locator(selector).first().evaluate((element) => {
        const rect = element.getBoundingClientRect();
        const style = window.getComputedStyle(element);
        const lineHeight = Number.parseFloat(style.lineHeight);
        return rect.height <= Math.max(36, lineHeight * 1.75);
      }),
    )
    .toBe(true);
}

export async function assertSingleVisibleByTestId(page: Page, testId: string) {
  const locator = page.getByTestId(testId);
  await expect(locator).toHaveCount(1);
  await expect(locator).toBeVisible();
  return locator;
}

async function clickOptionalButton(page: Page, name: string | RegExp) {
  const button = page.getByRole("button", { name }).first();
  if (await button.isVisible({ timeout: 5_000 }).catch(() => false)) {
    await button.click();
  }
}

export async function backendFetch<T>(page: Page, path: string, init: BrowserFetchInit = {}) {
  return page.evaluate(
    async ({ path: requestPath, init: requestInit }) => {
      const method = requestInit.method?.toUpperCase() ?? "GET";
      const headers: Record<string, string> = { ...(requestInit.headers ?? {}) };

      if (!["GET", "HEAD", "OPTIONS"].includes(method)) {
        const csrfResponse = await fetch(`${window.location.origin}/api/auth/csrf`, {
          credentials: "include",
          cache: "no-store",
        });
        if (!csrfResponse.ok) {
          throw new Error(`CSRF request failed: ${csrfResponse.status}`);
        }
        const csrf = (await csrfResponse.json()) as CsrfResponse;
        headers[csrf.headerName] = csrf.token;
      }

      const body =
        typeof requestInit.body === "undefined"
          ? undefined
          : typeof requestInit.body === "string"
            ? requestInit.body
            : JSON.stringify(requestInit.body);

      if (body && !headers["Content-Type"]) {
        headers["Content-Type"] = "application/json";
      }

      const requestUrl = `${window.location.origin}${requestPath}`;
      const response = await fetch(requestUrl, {
        method,
        headers,
        body,
        credentials: "include",
        cache: "no-store",
      });

      if (!response.ok) {
        throw new Error(`Backend request failed: ${response.status} ${await response.text()}`);
      }

      if (response.status === 204) {
        return null as T;
      }

      const contentType = response.headers.get("content-type") ?? "";
      return (contentType.includes("application/json") ? response.json() : response.text()) as T;
    },
    { path, init },
  );
}

function getKoreaNowParts() {
  const formatter = new Intl.DateTimeFormat("en-US", {
    weekday: "long",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
    timeZone: KOREA_TIME_ZONE,
  });
  const parts = Object.fromEntries(
    formatter.formatToParts(new Date()).map((part) => [part.type, part.value]),
  );

  return {
    weekday: parts.weekday ?? "Monday",
    hour: Number(parts.hour ?? "0"),
    minute: Number(parts.minute ?? "0"),
  };
}

function formatMinutes(totalMinutes: number) {
  const hour = Math.floor(totalMinutes / 60);
  const minute = totalMinutes % 60;
  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
}

function minutesOfDay(value: string) {
  const [hour = "0", minute = "0"] = value.split(":");
  return Number(hour) * 60 + Number(minute);
}

function toWeekInterval(dayOfWeek: string, startTime: string, endTime: string) {
  const start = dayIndex(dayOfWeek) * 24 * 60 + minutesOfDay(startTime);
  const endMinute = minutesOfDay(endTime);
  const startMinute = minutesOfDay(startTime);
  const duration = endMinute <= startMinute ? endMinute + 24 * 60 - startMinute : endMinute - startMinute;

  return {
    start,
    end: start + duration,
  };
}

function weekIntervalsOverlap(
  left: { start: number; end: number },
  right: { start: number; end: number },
) {
  return intervalsOverlap(left.start, left.end, right.start, right.end)
    || intervalsOverlap(left.start, left.end, right.start + WEEK_MINUTES, right.end + WEEK_MINUTES)
    || intervalsOverlap(left.start + WEEK_MINUTES, left.end + WEEK_MINUTES, right.start, right.end);
}

function intervalsOverlap(leftStart: number, leftEnd: number, rightStart: number, rightEnd: number) {
  return leftStart < rightEnd && rightStart < leftEnd;
}

function dayIndex(dayOfWeek: string) {
  const backendDay = BACKEND_DAY_BY_WEEKDAY[dayOfWeek] ?? dayOfWeek;
  const index = WEEK_DAY_ORDER.indexOf(backendDay);
  return index === -1 ? 0 : index;
}
