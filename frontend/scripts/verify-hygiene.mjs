import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = process.cwd();
const e2eDir = path.join(root, "e2e");
const nextEnv = path.join(root, "next-env.d.ts");
const productSourceDirs = ["app", "components", "lib"].map((dir) => path.join(root, dir));
const apiModule = path.join(root, "lib", "api.ts");

const failures = [];

function walk(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      return walk(fullPath);
    }
    return fullPath;
  });
}

function fail(message) {
  failures.push(message);
}

function relativePath(file) {
  return path.relative(root, file).replaceAll(path.sep, "/");
}

for (const file of walk(e2eDir).filter((item) => item.endsWith(".ts"))) {
  const text = fs.readFileSync(file, "utf8");
  const relative = relativePath(file);

  if (/\b(test|describe)\.only\s*\(/.test(text)) {
    fail(`${relative}: focused Playwright test committed`);
  }

  if (/BANNED_(USER_COPY|AI_METADATA)\s*=/.test(text) && !relative.endsWith("helpers.ts")) {
    fail(`${relative}: banned user-copy policy must stay centralized in e2e/helpers.ts`);
  }
}

for (const file of fs.readdirSync(e2eDir)) {
  if (/^(tmp|temp|scratch|debug)-.+\.(ts|js|mjs|cjs)$/.test(file)) {
    fail(`e2e/${file}: temporary spec/helper file must not be committed`);
  }
}

if (fs.existsSync(nextEnv)) {
  const nextEnvText = fs.readFileSync(nextEnv, "utf8");
  if (nextEnvText.includes("./.next/dev/")) {
    fail("next-env.d.ts points at .next/dev generated types; run a production build or revert generated drift");
  }
}

for (const file of productSourceDirs.flatMap((dir) => (fs.existsSync(dir) ? walk(dir) : [])).filter((item) => /\.(tsx?|jsx?)$/.test(item))) {
  const text = fs.readFileSync(file, "utf8");
  const relative = relativePath(file);

  if (/(^|[^\w.])window\.confirm\s*\(|(^|[^\w.])confirm\s*\(/.test(text)) {
    fail(`${relative}: product UI must use the app-native ConfirmDialog, not browser confirm()`);
  }

  if (/\blocalStorage\b/.test(text)) {
    fail(`${relative}: localStorage must not be used for canonical app/onboarding state`);
  }
}

if (fs.existsSync(apiModule)) {
  const apiText = fs.readFileSync(apiModule, "utf8");
  const rawAllowed = new Set([
    "getSession",
    "getLoginStart",
    "getOnboardingStatus",
    "bootstrapOnboarding",
    "saveOnboardingAnswers",
    "completeOnboarding",
    "logout",
    "getWeekSchedule",
    "createScheduleBlock",
    "updateScheduleBlock",
    "deleteScheduleBlock",
  ]);

  const methodPattern = /\n  (?:async\s+)?([a-zA-Z0-9_]+)\([^)]*\)\s*\{([\s\S]*?)(?=\n  (?:async\s+)?[a-zA-Z0-9_]+\([^)]*\)\s*\{|\n};)/g;
  for (const match of apiText.matchAll(methodPattern)) {
    const [, methodName, body] = match;
    const usesRaw = /\brequestRaw\s*</.test(body) || /\brequestRaw\s*\(/.test(body);
    const usesEnvelope = /\brequestEnvelope\s*</.test(body) || /\brequestEnvelope\s*\(/.test(body);

    if (usesRaw && !rawAllowed.has(methodName)) {
      fail(`lib/api.ts:${methodName}: requestRaw is not allowed without an explicit contract exception`);
    }

    if (!usesRaw && !usesEnvelope && methodName !== "deleteFocusItem") {
      fail(`lib/api.ts:${methodName}: API methods must route through requestRaw or requestEnvelope`);
    }
  }
}

if (failures.length) {
  console.error(["Hygiene verification failed:", ...failures.map((failure) => `- ${failure}`)].join("\n"));
  process.exit(1);
}

console.log("Hygiene verification passed.");
