import { expect, test, type Page } from "@playwright/test";
import fs from "node:fs/promises";
import path from "node:path";

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
  getCurrentBackendDay,
  loginAsUniqueMockUser,
} from "./helpers";

interface ApiEnvelope<T> {
  data: T;
}

interface SuggestionResponse {
  id: string;
  status: string;
  executable: boolean;
}

const ROOT_DIR = path.resolve(process.cwd(), "..");
const REPORT_DIR = path.join(ROOT_DIR, ".omx", "reports", "service-improvement-qa");
const SCREENSHOT_DIR = path.join(ROOT_DIR, ".omx", "screenshots", "service-improvement-qa");

async function capture(page: Page, name: string, size: { width: number; height: number }) {
  await page.setViewportSize(size);
  await page.waitForLoadState("networkidle").catch(() => undefined);
  const screenshotPath = path.join(SCREENSHOT_DIR, `${name}-${size.width}.png`);
  await page.screenshot({ path: screenshotPath, fullPage: true });
  return path.relative(ROOT_DIR, screenshotPath).replaceAll("\\", "/");
}

async function ensurePendingSuggestion(page: Page) {
  const suggestions = await backendFetch<ApiEnvelope<SuggestionResponse[]>>(page, "/api/agent/suggestions");
  const pending = suggestions.data.find((item) => item.status === "pending");
  if (pending) {
    return pending;
  }

  const created = await backendFetch<ApiEnvelope<SuggestionResponse>>(page, "/api/agent/reschedule", {
    method: "POST",
    body: {
      triggerType: "manual_request",
      reason: "오늘 일정 변경 요청: 오후 회의 전 준비 시간을 확보하고, 겹치는 작업은 무리 없이 뒤로 미뤄줘.",
    },
  });
  expect(created.data.status).toBe("pending");
  return created.data;
}

async function expectPendingSuggestionAction(page: Page, suggestion: Pick<SuggestionResponse, "executable">) {
  await expect(page.getByRole("button", { name: "보류" })).toBeVisible();

  if (suggestion.executable) {
    await expect(page.getByRole("button", { name: "적용" })).toBeEnabled();
    return;
  }

  await expect(page.getByRole("button", { name: /적용할 변경 없음|다시 요청 필요/ })).toBeDisabled();
}

test("simplified schedule UX keeps only today, now, weekly stack, edit controls, and AI input", async ({
  page,
}, testInfo) => {
  test.slow();
  await fs.mkdir(REPORT_DIR, { recursive: true });
  await fs.mkdir(SCREENSHOT_DIR, { recursive: true });

  const screenshots: string[] = [];

  await page.goto("/login");
  await expect(page.getByRole("button", { name: /Google로 시작|로그인 준비 중/ })).toBeVisible({ timeout: 30_000 });
  await assertNoInternalUserCopy(page);
  screenshots.push(await capture(page, "desktop-login", { width: 1440, height: 1000 }));
  screenshots.push(await capture(page, "mobile-login", { width: 390, height: 1000 }));

  await loginAsUniqueMockUser(page, testInfo, { connectGoogle: true, writeCapable: false });
  if (new URL(page.url()).pathname.includes("/onboarding")) {
    await expect(page.getByRole("button", { name: /저장하고 계속|둘러보기|적용하고 시작/ }).first()).toBeVisible({
      timeout: 45_000,
    });
    await assertNoInternalUserCopy(page);
    screenshots.push(await capture(page, "desktop-onboarding", { width: 1440, height: 1000 }));
    screenshots.push(await capture(page, "mobile-onboarding", { width: 390, height: 1000 }));
  }

  await completeOnboardingIfPresent(page);
  const today = getCurrentBackendDay();
  await clearScheduleBlocksForDay(page, today);
  const activeBlock = buildActiveScheduleBlock(`지금 해야 하는 일 ${Date.now()}`);
  await createScheduleBlockViaApi(page, activeBlock);

  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: /오늘 일정은|오늘 예정된 일정이 없습니다/ }).first()).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.locator("body")).toContainText(/지금\/다음|지금 진행 중|실행 모드 시작/);
  await assertNoInternalUserCopy(page);
  screenshots.push(await capture(page, "desktop-dashboard", { width: 1440, height: 1000 }));
  screenshots.push(await capture(page, "mobile-dashboard", { width: 390, height: 1000 }));

  const pendingSuggestion = await ensurePendingSuggestion(page);
  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: /최적화 제안|확인이 필요합니다\.|지금은 적용할 수 없습니다./ })).toBeVisible({ timeout: 30_000 });
  await expectPendingSuggestionAction(page, pendingSuggestion);
  await assertNoInternalUserCopy(page);
  screenshots.push(await capture(page, "mobile-dashboard-pending", { width: 390, height: 1000 }));

  await page.goto("/schedule");
  await expect(page.getByTestId("schedule-add-button")).toBeVisible({ timeout: 30_000 });
  await assertSingleVisibleByTestId(page, "schedule-ai-right-rail");
  await assertSingleVisibleByTestId(page, "schedule-ai-request-input");
  await assertSingleVisibleByTestId(page, "schedule-ai-request-submit");
  await assertSingleLineBySelector(page, "[data-testid='schedule-pending-count']");
  await assertSelectorTextDoesNotOverflow(page, ".ai-chat-thread");
  await assertSelectorTextDoesNotOverflow(page, ".chat-bubble.user > p");
  await assertNoHorizontalOverflow(page);
  await expect(page.locator("body")).toContainText(/오늘 일정|이번 주|주간 일정/);
  await assertNoInternalUserCopy(page);
  screenshots.push(await capture(page, "desktop-schedule", { width: 1440, height: 1000 }));
  screenshots.push(await capture(page, "mobile-schedule", { width: 390, height: 1000 }));

  await page.goto("/focus");
  await expect(page.getByText(/지금 실행|지금 할 일/).first()).toBeVisible({ timeout: 30_000 });
  await assertNoInternalUserCopy(page);
  screenshots.push(await capture(page, "desktop-focus", { width: 1440, height: 1000 }));
  screenshots.push(await capture(page, "mobile-focus", { width: 390, height: 1000 }));

  await fs.writeFile(
    path.join(REPORT_DIR, "qa-observations.json"),
    `${JSON.stringify(
      {
        generatedAt: new Date().toISOString(),
        scenarios: [
          "login hides sync/status clutter",
          "dashboard starts with today/now",
          "pending changes distinguish approval and non-executable states",
          "schedule keeps weekly stack, edit controls, and AI input",
          "focus keeps now task only",
          "mobile and desktop screenshots captured",
        ],
        screenshots,
      },
      null,
      2,
    )}\n`,
  );
});
