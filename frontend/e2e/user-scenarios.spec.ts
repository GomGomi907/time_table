import { expect, test } from "@playwright/test";

import {
  backendFetch,
  completeOnboardingIfPresent,
  escapeRegExp,
  loginAsUniqueMockUser,
} from "./helpers";

interface ApiEnvelope<T> {
  data: T;
  meta?: Record<string, unknown>;
}

interface TaskResponse {
  id: string;
  title: string;
  status: string;
}

interface EventResponse {
  id: string;
  title: string;
  status: string;
  endAt: string;
}

interface SuggestionResponse {
  id: string;
  status: string;
  summary: string;
  executable: boolean;
}

function minutesFromNow(minutes: number) {
  return new Date(Date.now() + minutes * 60 * 1000).toISOString();
}

async function clearPendingSuggestions(page: Parameters<typeof backendFetch>[0]) {
  const suggestions = await backendFetch<ApiEnvelope<SuggestionResponse[]>>(page, "/api/agent/suggestions");
  for (const suggestion of suggestions.data.filter((item) => item.status === "pending")) {
    await backendFetch<ApiEnvelope<SuggestionResponse>>(
      page,
      `/api/agent/suggestions/${suggestion.id}/reject`,
      {
        method: "POST",
        body: { reason: "E2E scenario setup" },
      },
    );
  }
}

test.describe("핵심 사용자 시나리오", () => {
  test("사용자는 로그인 후 로그아웃해서 접근 안내로 돌아갈 수 있다", async ({ page }, testInfo) => {
    await loginAsUniqueMockUser(page, testInfo);
    await completeOnboardingIfPresent(page);

    await page.goto("/dashboard");
    await expect(page.getByRole("heading", { name: /오늘 일정/ }).first()).toBeVisible({
      timeout: 30_000,
    });

    await page.getByRole("button", { name: "로그아웃" }).click();
    const loginStart = page.getByRole("button", { name: "Google로 시작" });
    if (!(await loginStart.isVisible({ timeout: 5_000 }).catch(() => false))) {
      await page.goto("/login");
    }
    await expect(page.getByRole("heading", { name: "오늘 일정과 지금 할 일을 바로 보여줍니다." })).toBeVisible({
      timeout: 30_000,
    });
    await expect(loginStart).toBeVisible();
  });

  test("사용자는 빈 조정 요청을 막고, 요청 생성 후 보류할 수 있다", async ({ page }, testInfo) => {
    await loginAsUniqueMockUser(page, testInfo);
    await completeOnboardingIfPresent(page);
    await clearPendingSuggestions(page);

    await page.goto("/schedule");
    await expect(page.getByRole("button", { name: "요청 보내기" })).toBeVisible({
      timeout: 30_000,
    });

    await page.getByRole("button", { name: "요청 보내기" }).click();
    await expect(page.getByRole("status")).toContainText("요청 내용이 필요합니다.");

    const reason = `E2E 일정 조정 요청 ${Date.now()}`;
    await page.getByPlaceholder(/내일 오전 회의 준비/).fill(reason);
    await page.getByRole("button", { name: "요청 보내기" }).click();

    await expect(page.getByRole("status")).toContainText("변경 요청을 만들었습니다.", {
      timeout: 30_000,
    });
    await expect(page.getByText("보류").first()).toBeVisible({ timeout: 30_000 });

    await page.getByRole("button", { name: "보류" }).first().click();
    await expect(page.getByRole("status")).toContainText("변경을 보류했습니다.", {
      timeout: 30_000,
    });
    await expect(page.getByText("보류").first()).toHaveCount(0, { timeout: 30_000 });
  });

  test("사용자는 할 일을 시작하고 완료할 수 있다", async ({ page }, testInfo) => {
    await loginAsUniqueMockUser(page, testInfo);
    await completeOnboardingIfPresent(page);

    const title = `E2E 할 일 ${Date.now()}`;
    const task = await backendFetch<ApiEnvelope<TaskResponse>>(page, "/api/tasks", {
      method: "POST",
      body: {
        title,
        description: "사용자 시나리오 QA",
        dueDate: minutesFromNow(90),
        estimatedMinutes: 25,
        priority: 1,
        goalId: null,
        category: "WORK",
      },
    });
    expect(task.data.status).toBe("TODO");

    await page.goto("/focus");
    await expect(page.getByRole("heading", { name: new RegExp(escapeRegExp(title)) })).toBeVisible({
      timeout: 30_000,
    });

    await page.getByRole("button", { name: "할 일 시작" }).click();
    await expect(page.getByRole("status")).toContainText("할 일을 시작했습니다.", {
      timeout: 30_000,
    });
    await expect(page.getByRole("button", { name: "할 일 완료" })).toBeVisible({ timeout: 30_000 });

    await page.getByRole("button", { name: "할 일 완료" }).click();
    await expect(page.getByRole("status")).toContainText("현재 집중 항목을 완료했습니다.", {
      timeout: 30_000,
    });

    const completed = await backendFetch<ApiEnvelope<TaskResponse[]>>(page, "/api/tasks");
    expect(completed.data.find((item) => item.id === task.data.id)?.status).toBe("DONE");
  });

  test("사용자가 현재 일정을 미루면 변경 요청을 같은 화면에서 보류할 수 있다", async ({ page }, testInfo) => {
    await loginAsUniqueMockUser(page, testInfo);
    await completeOnboardingIfPresent(page);
    await clearPendingSuggestions(page);
    const beforeSuggestions = await backendFetch<ApiEnvelope<SuggestionResponse[]>>(page, "/api/agent/suggestions");
    const beforePendingCount = beforeSuggestions.data.filter((suggestion) => suggestion.status === "pending").length;

    const title = `E2E 미루기 일정 ${Date.now()}`;
    const created = await backendFetch<ApiEnvelope<EventResponse>>(page, "/api/events", {
      method: "POST",
      body: {
        title,
        description: "사용자 시나리오 QA",
        startAt: minutesFromNow(-5),
        endAt: minutesFromNow(35),
        priority: 2,
        category: "WORK",
        goalId: null,
      },
    });
    expect(created.data.status).toBe("PLANNED");

    await page.goto("/focus");
    await expect(page.getByRole("heading", { name: new RegExp(escapeRegExp(title)) })).toBeVisible({
      timeout: 30_000,
    });

    await page.getByRole("button", { name: "미루기" }).click();
    await expect(page.getByRole("status")).toContainText("현재 항목을 미루고 변경이 필요한 항목으로 표시했습니다.", {
      timeout: 30_000,
    });

    const suggestions = await backendFetch<ApiEnvelope<SuggestionResponse[]>>(page, "/api/agent/suggestions");
    const pendingSuggestions = suggestions.data.filter((suggestion) => suggestion.status === "pending");
    const afterPendingCount = pendingSuggestions.length;
    expect(afterPendingCount).toBeGreaterThan(beforePendingCount);

    await expect(page.getByText("변경 요청")).toBeVisible({ timeout: 30_000 });
    if (pendingSuggestions.some((suggestion) => suggestion.executable)) {
      await expect(page.getByRole("button", { name: "적용" })).toBeEnabled({ timeout: 30_000 });
    } else {
      await expect(page.getByRole("button", { name: /적용할 변경 없음|다시 요청 필요/ })).toBeDisabled({
        timeout: 30_000,
      });
    }
    await expect(page.getByRole("button", { name: "보류" })).toBeVisible({ timeout: 30_000 });

    await page.getByRole("button", { name: "보류" }).click();
    await expect(page.getByRole("status")).toContainText("변경을 보류했습니다.", {
      timeout: 30_000,
    });
    await expect(page.getByText("변경 요청")).toBeHidden({ timeout: 30_000 });

    const rejectedSuggestions = await backendFetch<ApiEnvelope<SuggestionResponse[]>>(page, "/api/agent/suggestions");
    expect(rejectedSuggestions.data.filter((suggestion) => suggestion.status === "pending")).toHaveLength(0);

    await page.goto("/schedule");
    await expect(page.getByRole("button", { name: "보류" }).first()).toBeHidden({ timeout: 30_000 });
  });
});
