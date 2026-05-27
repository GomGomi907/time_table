import { expect, test, type Page } from "@playwright/test";
import fs from "node:fs/promises";
import path from "node:path";

import {
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
}

const ROOT_DIR = path.resolve(process.cwd(), "..");
const REPORT_DIR = path.join(ROOT_DIR, ".omx", "reports", "service-improvement-qa");
const SCREENSHOT_DIR = path.join(ROOT_DIR, ".omx", "screenshots", "service-improvement-qa");
const BANNED_USER_COPY =
  /Google 연결|Google 계정 연결됨|Google 읽기|Google 반영 대기|마지막 동기화|연결 상태 확인|근거|기준으로 재배치|AI 비서 메모|예상 영향|확인한 내용|권한 상태|조정안 핵심 요약|추천 집중|실제 집중 상태|접어/;

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
      reason: "오늘 일정 변경 요청",
    },
  });
  expect(created.data.status).toBe("pending");
  return created.data;
}

test("simplified schedule UX keeps only today, now, weekly table, edit controls, and AI input", async ({
  page,
}, testInfo) => {
  test.slow();
  await fs.mkdir(REPORT_DIR, { recursive: true });
  await fs.mkdir(SCREENSHOT_DIR, { recursive: true });

  const screenshots: string[] = [];

  await page.goto("/login");
  await expect(page.getByRole("button", { name: /Google로 시작|로그인 준비 중/ })).toBeVisible({ timeout: 30_000 });
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
  screenshots.push(await capture(page, "desktop-login", { width: 1440, height: 1000 }));
  screenshots.push(await capture(page, "mobile-login", { width: 390, height: 1000 }));

  await loginAsUniqueMockUser(page, testInfo, { connectGoogle: true, writeCapable: false });
  if (new URL(page.url()).pathname.includes("/onboarding")) {
    await expect(page.getByRole("button", { name: /저장하고 계속|둘러보기|적용하고 시작/ }).first()).toBeVisible({
      timeout: 45_000,
    });
    await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
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
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
  screenshots.push(await capture(page, "desktop-dashboard", { width: 1440, height: 1000 }));
  screenshots.push(await capture(page, "mobile-dashboard", { width: 390, height: 1000 }));

  await ensurePendingSuggestion(page);
  await page.goto("/dashboard");
  await expect(page.getByRole("heading", { name: "적용하거나 보류하세요." })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole("button", { name: "보류" })).toBeVisible();
  await expect(page.getByRole("button", { name: "적용" })).toBeVisible();
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
  screenshots.push(await capture(page, "mobile-dashboard-pending", { width: 390, height: 1000 }));

  await page.goto("/schedule");
  await expect(page.getByRole("button", { name: "일정 직접 추가" })).toBeVisible({ timeout: 30_000 });
  await expect(page.getByPlaceholder("예: 내일 오전 회의 준비 시간을 비워줘")).toBeVisible({
    timeout: 30_000,
  });
  await expect(page.getByRole("button", { name: "요청 보내기" })).toBeVisible();
  await expect(page.locator("body")).toContainText(/오늘 일정|이번 주|주간 일정/);
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
  screenshots.push(await capture(page, "desktop-schedule", { width: 1440, height: 1000 }));
  screenshots.push(await capture(page, "mobile-schedule", { width: 390, height: 1000 }));

  await page.goto("/focus");
  await expect(page.getByText(/지금 실행|지금 할 일/).first()).toBeVisible({ timeout: 30_000 });
  await expect(page.locator("body")).not.toContainText(BANNED_USER_COPY);
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
          "pending changes expose only apply/reject",
          "schedule keeps weekly table, edit controls, and AI input",
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
