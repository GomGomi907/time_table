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

  await page.goto("/terms");
  await expect(page.getByRole("heading", { name: "서비스 약관" })).toBeVisible();
  await expect(page.getByRole("link", { name: "개인정보처리방침" }).first()).toHaveAttribute("href", "/privacy");
});

test("login page keeps legal links visible before authentication", async ({ page }) => {
  await page.goto("/login");

  await expect(page.getByRole("img", { name: "Time Table 로고" })).toBeVisible();
  await expect(page.getByRole("link", { name: "서비스 소개" })).toHaveAttribute("href", "/");
  await expect(page.getByRole("link", { name: "개인정보처리방침" })).toHaveAttribute("href", "/privacy");
  await expect(page.getByRole("link", { name: "서비스 약관" })).toHaveAttribute("href", "/terms");
});
