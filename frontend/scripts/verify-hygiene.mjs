import fs from "node:fs";
import path from "node:path";
import process from "node:process";

const root = process.cwd();
const e2eDir = path.join(root, "e2e");
const nextEnv = path.join(root, "next-env.d.ts");

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

for (const file of walk(e2eDir).filter((item) => item.endsWith(".ts"))) {
  const text = fs.readFileSync(file, "utf8");
  const relative = path.relative(root, file).replaceAll(path.sep, "/");

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

if (failures.length) {
  console.error(["Hygiene verification failed:", ...failures.map((failure) => `- ${failure}`)].join("\n"));
  process.exit(1);
}

console.log("Hygiene verification passed.");
