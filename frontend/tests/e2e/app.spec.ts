import { expect, test, type Page, type Route } from "@playwright/test";

const sessionResponse = {
  authenticated: true,
  userId: "demo-user",
  email: "demo@timetable.app",
  displayName: "김하늘",
  googleConnectionStatus: "CONNECTED",
  lastSyncAt: "2026-04-19T09:00:00Z",
  callbackUrl: "http://localhost:3000/auth/callback",
} as const;

const goalsResponse = [
  {
    id: "goal-1",
    parentId: null,
    title: "기획 문서 완성",
    description: "오늘 안에 초안 마감",
    category: "CAREER",
    status: "IN_PROGRESS",
    progress: 68,
  },
  {
    id: "goal-2",
    parentId: null,
    title: "운동 루틴 유지",
    description: "저녁 30분 걷기",
    category: "HEALTH",
    status: "PENDING",
    progress: 20,
  },
  {
    id: "goal-3",
    parentId: null,
    title: "주간 회고 정리",
    description: null,
    category: "GROWTH",
    status: "COMPLETED",
    progress: 100,
  },
] as const;

const settingsResponse = {
  id: "settings-1",
  quietHoursStart: "22:00:00",
  quietHoursEnd: "08:00:00",
  bufferMinutes: 10,
  overtimeTriggerMinutes: 15,
  openGapTriggerMinutes: 30,
  interventionFrequency: "balanced",
} as const;

const tasksResponse = [
  {
    id: "task-1",
    title: "리뷰 피드백 반영",
    notes: "대시보드 문구와 레이아웃 조정",
    status: "pending",
  },
  {
    id: "task-2",
    title: "설정 저장 확인",
    notes: "엔진 파라미터 저장 후 새로고침 검증",
    status: "completed",
  },
] as const;

const dayKeys = [
  "Sunday",
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
] as const;

const clampMinutes = (minutes: number) => Math.max(0, Math.min(minutes, 23 * 60 + 59));

const formatTime = (minutes: number) => {
  const safeMinutes = clampMinutes(minutes);
  const hour = Math.floor(safeMinutes / 60)
    .toString()
    .padStart(2, "0");
  const minute = (safeMinutes % 60).toString().padStart(2, "0");
  return `${hour}:${minute}`;
};

function buildScheduleResponse(now = new Date()) {
  const currentMinutes = now.getHours() * 60 + now.getMinutes();
  const anchor = Math.min(Math.max(currentMinutes, 9 * 60 + 30), 20 * 60);
  const currentDay = dayKeys[now.getDay()];
  const baseBlocks = {
    Monday: [
      { id: "mon-1", startTime: "09:00", endTime: "10:30", activity: "주간 계획 점검", category: "ADMIN", note: "회의 전 우선순위 정리", sourceType: "MANUAL", sourceRef: null },
      { id: "mon-2", startTime: "14:00", endTime: "16:00", activity: "집중 작업", category: "WORK", note: "문서와 화면 정리", sourceType: "DEFAULT_ROUTINE", sourceRef: null },
    ],
    Tuesday: [
      { id: "tue-1", startTime: "10:00", endTime: "11:00", activity: "고객 미팅", category: "WORK", note: null, sourceType: "DEFAULT_ROUTINE", sourceRef: null },
      { id: "tue-2", startTime: "19:30", endTime: "20:30", activity: "영어 학습", category: "GROWTH", note: null, sourceType: "GEMMA_IMPORT", sourceRef: null },
    ],
    Wednesday: [
      { id: "wed-1", startTime: "08:30", endTime: "09:30", activity: "운동", category: "LIFE", note: "가볍게 러닝", sourceType: "MANUAL", sourceRef: null },
      { id: "wed-2", startTime: "13:00", endTime: "15:00", activity: "기획 검토", category: "WORK", note: null, sourceType: "DEFAULT_ROUTINE", sourceRef: null },
    ],
    Thursday: [
      { id: "thu-1", startTime: "09:30", endTime: "11:30", activity: "리서치 정리", category: "WORK", note: null, sourceType: "DEFAULT_ROUTINE", sourceRef: null },
    ],
    Friday: [
      { id: "fri-1", startTime: "11:00", endTime: "12:00", activity: "주간 회고", category: "ADMIN", note: "다음 주 준비", sourceType: "MANUAL", sourceRef: null },
      { id: "fri-2", startTime: "15:00", endTime: "17:00", activity: "마감 작업", category: "WORK", note: null, sourceType: "DEFAULT_ROUTINE", sourceRef: null },
    ],
    Saturday: [
      { id: "sat-1", startTime: "10:00", endTime: "11:30", activity: "개인 프로젝트", category: "HOBBY", note: null, sourceType: "MANUAL", sourceRef: null },
    ],
    Sunday: [
      { id: "sun-1", startTime: "16:00", endTime: "17:00", activity: "주간 정리", category: "ADMIN", note: null, sourceType: "GEMMA_IMPORT", sourceRef: null },
    ],
  } as const;

  return {
    week: dayKeys.map((dayKey) => ({
      dayOfWeek: dayKey,
      blocks:
        dayKey === currentDay
          ? [
              {
                id: "block-1",
                startTime: formatTime(anchor - 45),
                endTime: formatTime(anchor + 50),
                activity: "문서 작성 집중",
                category: "WORK",
                note: "초안 정리와 검토",
                sourceType: "MANUAL",
                sourceRef: null,
              },
              {
                id: "block-2",
                startTime: formatTime(anchor + 65),
                endTime: formatTime(anchor + 120),
                activity: "주간 시간표 정리",
                category: "ADMIN",
                note: "오후 일정 다시 배치",
                sourceType: "MANUAL",
                sourceRef: null,
              },
              {
                id: "block-3",
                startTime: formatTime(anchor + 150),
                endTime: formatTime(anchor + 210),
                activity: "리뷰 미팅",
                category: "WORK",
                note: null,
                sourceType: "DEFAULT_ROUTINE",
                sourceRef: null,
              },
            ]
          : [...(baseBlocks[dayKey] ?? [])],
    })),
  };
}

const fulfillJson = (route: Route, json: unknown, status = 200) =>
  route.fulfill({
    status,
    contentType: "application/json",
    body: JSON.stringify(json),
  });

async function enableDarkTheme(page: Page) {
  await page.addInitScript(() => {
    window.localStorage.setItem("tt-theme", "dark");
    document.documentElement.dataset.theme = "dark";
  });
}

async function mockGuestLogin(page: Page) {
  await page.route("http://localhost:8080/api/**", async (route) => {
    const pathname = new URL(route.request().url()).pathname;

    if (pathname.endsWith("/auth/session")) {
      return fulfillJson(route, {
        authenticated: false,
        userId: "",
        email: null,
        displayName: null,
        googleConnectionStatus: "NOT_CONNECTED",
        lastSyncAt: null,
        callbackUrl: "http://localhost:3000/auth/callback",
      });
    }

    if (pathname.endsWith("/auth/google/start")) {
      return fulfillJson(route, {
        enabled: true,
        url: "https://accounts.google.com/o/oauth2/v2/auth",
        message: null,
      });
    }

    return fulfillJson(route, { message: "not mocked" }, 404);
  });
}

async function mockAuthenticatedDashboard(page: Page) {
  await page.route("http://localhost:8080/api/**", async (route) => {
    const pathname = new URL(route.request().url()).pathname;
    const method = route.request().method();

    if (pathname.endsWith("/auth/session")) {
      return fulfillJson(route, sessionResponse);
    }

    if (pathname.endsWith("/auth/google/start")) {
      return fulfillJson(route, {
        enabled: true,
        url: "https://accounts.google.com/o/oauth2/v2/auth",
        message: null,
      });
    }

    if (pathname.endsWith("/schedule/week")) {
      return fulfillJson(route, buildScheduleResponse());
    }

    if (pathname.endsWith("/goals")) {
      if (method === "GET") {
        return fulfillJson(route, goalsResponse);
      }

      if (method === "POST") {
        return fulfillJson(route, {
          id: "goal-new",
          parentId: null,
          title: "새 목표",
          description: null,
          category: "GROWTH",
          status: "PENDING",
          progress: 0,
        });
      }
    }

    if (pathname.endsWith("/settings")) {
      if (method === "GET" || method === "PUT") {
        return fulfillJson(route, settingsResponse);
      }
    }

    if (pathname.endsWith("/tasks")) {
      return fulfillJson(route, tasksResponse);
    }

    if (pathname.endsWith("/auth/logout")) {
      return route.fulfill({ status: 204 });
    }

    return fulfillJson(route, { message: "not mocked" }, 404);
  });
}

test("로그인 화면이 제품답게 보인다", async ({ page }, testInfo) => {
  await mockGuestLogin(page);

  await page.goto("/login");

  await expect(
    page.getByRole("heading", { name: "오늘 일정부터 차분하게 정리하세요" })
  ).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Google로 로그인" })
  ).toBeVisible();
  await expect(page.getByText("첫 화면", { exact: true })).toBeVisible();
  await expect(page.getByText("주간 흐름", { exact: true })).toBeVisible();
  await expect(page.getByText("운영 제어", { exact: true })).toBeVisible();

  await page.screenshot({
    path: testInfo.outputPath("login-page.png"),
    fullPage: true,
  });
});

test("오늘 화면이 현재 블록 중심으로 정리되어 보인다", async ({ page }, testInfo) => {
  await mockAuthenticatedDashboard(page);

  await page.goto("/dashboard");

  await expect(page.getByRole("heading", { name: "오늘의 집중" })).toBeVisible();
  await expect(page.getByText("문서 작성 집중")).toBeVisible();
  await expect(page.getByText("초안 정리와 검토")).toBeVisible();
  await expect(page.getByRole("link", { name: /시간표 보기/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /목표 보기/ })).toBeVisible();
  await expect(page.getByRole("heading", { name: "할 일 점검" })).toBeVisible();
  await expect(page.getByText("리뷰 피드백 반영")).toBeVisible();

  await page.screenshot({
    path: testInfo.outputPath("dashboard-page.png"),
    fullPage: true,
  });
});

test("로그인 화면의 다크 테마를 시각 점검한다", async ({ page }, testInfo) => {
  await enableDarkTheme(page);
  await mockGuestLogin(page);

  await page.goto("/login");

  await expect(
    page.getByRole("heading", { name: "오늘 일정부터 차분하게 정리하세요" })
  ).toBeVisible();

  await page.screenshot({
    path: testInfo.outputPath("login-page-dark.png"),
    fullPage: true,
  });
});

test("오늘 화면의 다크 테마를 시각 점검한다", async ({ page }, testInfo) => {
  await enableDarkTheme(page);
  await mockAuthenticatedDashboard(page);

  await page.goto("/dashboard");

  await expect(page.getByRole("heading", { name: "오늘의 집중" })).toBeVisible();

  await page.screenshot({
    path: testInfo.outputPath("dashboard-page-dark.png"),
    fullPage: true,
  });
});

test("주간 시간표 화면을 시각 점검한다", async ({ page }, testInfo) => {
  await mockAuthenticatedDashboard(page);

  await page.goto("/schedule");

  await expect(
    page.getByRole("heading", { name: "이번 주 시간표" })
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "새 일정 추가" })).toBeVisible();
  await expect(page.getByText("텍스트로 일정 반영")).toBeVisible();

  await page.screenshot({
    path: testInfo.outputPath("schedule-page.png"),
    fullPage: true,
  });
});

test("목표 화면을 시각 점검한다", async ({ page }, testInfo) => {
  await mockAuthenticatedDashboard(page);

  await page.goto("/goals");

  await expect(
    page.getByRole("heading", { name: "이번 주 목표", exact: true })
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "목표 추가" })).toBeVisible();

  await page.screenshot({
    path: testInfo.outputPath("goals-page.png"),
    fullPage: true,
  });
});

test("설정 화면을 시각 점검한다", async ({ page }, testInfo) => {
  await mockAuthenticatedDashboard(page);

  await page.goto("/settings");

  await expect(
    page.getByRole("heading", { name: "시스템 및 운영 설정" })
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "변경 내용 저장" })).toBeVisible();
  await expect(page.getByText("기본 분석 엔진")).toBeVisible();

  await page.screenshot({
    path: testInfo.outputPath("settings-page.png"),
    fullPage: true,
  });
});
