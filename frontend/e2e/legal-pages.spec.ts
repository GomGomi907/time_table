import { expect, test } from "@playwright/test";

test("public homepage exposes brand, privacy policy, and terms", async ({ page }) => {
  await page.goto("/");

  await expect(
    page.getByRole("heading", { name: /주간 계획, 오늘 실행, Google 일정 동기화/ }),
  ).toBeVisible();
  await expect(page.getByRole("img", { name: "Time Table 로고" })).toBeVisible();

  await page.getByRole("link", { name: "개인정보처리방침" }).click();
  await expect(page).toHaveURL(/\/privacy$/);
  await expect(page.getByRole("heading", { name: "개인정보처리방침" })).toBeVisible();
  await expect(page.getByText("Google API Services User Data Policy")).toBeVisible();
  await expect(page.getByText("Google 연결 해제를 실행하면 저장된 Google OAuth 토큰")).toBeVisible();
  await expect(page.getByRole("link", { name: "tkdrmsdl90715@gmail.com" }).first()).toHaveAttribute(
    "href",
    "mailto:tkdrmsdl90715@gmail.com",
  );

  await page.goto("/terms");
  await expect(page.getByRole("heading", { name: "서비스 약관" })).toBeVisible();
  await expect(page.getByRole("link", { name: "개인정보처리방침" }).first()).toHaveAttribute("href", "/privacy");
  await expect(page.getByText("Google 연결 해제와 계정·데이터 삭제를 직접 실행")).toBeVisible();
});

test("login page keeps legal links visible before authentication", async ({ page }) => {
  await page.goto("/login");

  await expect(page.getByRole("img", { name: "Time Table 로고" })).toBeVisible();
  await expect(page.getByRole("button", { name: /Google로 계속하기/ })).toBeVisible();
  await expect(page.getByText("사용자가 승인한 Calendar/Tasks 데이터만")).toBeVisible();
  await expect(page.getByRole("link", { name: "서비스 소개" })).toHaveAttribute("href", "/");
  await expect(page.getByRole("link", { name: "개인정보처리방침" })).toHaveAttribute("href", "/privacy");
  await expect(page.getByRole("link", { name: "서비스 약관" })).toHaveAttribute("href", "/terms");
});
