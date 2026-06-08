#!/usr/bin/env node
import { readFileSync, writeFileSync, mkdirSync, existsSync } from "node:fs";
import { dirname, join } from "node:path";

const root = process.cwd();
const patinaPatternPath = join(root, ".local", "patina", "node_modules", "patina-cli", "docs", "PATTERNS-KO.md");
if (!existsSync(patinaPatternPath)) {
  console.error("Patina KO pattern catalog not found. Run: npm install --prefix .local/patina patina-cli@4.0.0");
  process.exit(2);
}
const patternCatalog = readFileSync(patinaPatternPath, "utf8");

const targets = [
  "frontend/components/schedule-view.tsx",
  "frontend/components/suggestion-review-card.tsx",
  "frontend/lib/format.ts",
  "backend/src/main/java/com/timetable/operator/agent/application/AiCommandValidationService.java",
  "backend/src/main/java/com/timetable/operator/agent/application/policy/AssistantPolicyService.java",
  "backend/src/main/java/com/timetable/operator/agent/application/AiRescheduleClient.java",
  "backend/src/main/java/com/timetable/operator/agent/application/RescheduleSuggestionService.java",
  "backend/src/main/java/com/timetable/operator/agent/application/ChatCommandOrchestrationService.java",
];

const checks = [
  {
    id: "internal-ai-terms",
    label: "사용자에게 불필요한 내부 AI/시스템 용어",
    regex: /(초안|draft|후보 명령|제공자|검증|원본|투영|실행 가능한|응답 스키마|commandBatch|payload)/i,
    advice: "사용자가 볼 문장은 '확인할 변경', '반영 전', '연동 일정'처럼 행동 중심으로 바꾼다.",
  },
  {
    id: "ai-cliche-lexicon",
    label: "Patina KO: AI 특유 어휘/과장어",
    regex: /(체계적|효율적|효과적|적극적|종합적|핵심적|전략적|근본적|획기적|심층적|유기적|다양한|활발한|주목할 만한|이를 통해|나아가|도모|촉진|극대화)/,
    advice: "추상 형용사보다 사용자가 지금 해야 할 일과 결과를 짧게 쓴다.",
  },
  {
    id: "contrast-formula",
    label: "Patina KO: '단순히 X가 아니라 Y다'식 대비 공식",
    regex: /(단순히|단순한).{0,24}(아니라|아닌|넘어|그치지 않고)|뿐만 아니라|비단 .{0,16}뿐 아니라/,
    advice: "대비 수사 대신 실제 기능/행동을 바로 말한다.",
  },
  {
    id: "user-burden",
    label: "사용자에게 모든 정보를 다시 요구하는 문장",
    regex: /(모두 알려|더 구체적으로|정확한 .*다시 알려|다시 입력|다시 요청)/,
    advice: "앱이 이미 아는 맥락을 활용하고, 부족한 한두 가지만 묻는다.",
  },
  {
    id: "verbose-status",
    label: "긴 상태/오류 문장",
    regex: /[가-힣][^"'`]{58,}/,
    advice: "토스트/채팅 답변은 한 문장으로 줄이고 자세한 내용은 카드 본문에 맡긴다.",
  },
];

function koreanFragments(line) {
  if (isAuditConfigurationLine(line)) {
    return [];
  }
  const fragments = [];
  const quoted = line.matchAll(/"([^"\\]*(?:\\.[^"\\]*)*[가-힣][^"\\]*(?:\\.[^"\\]*)*)"/g);
  for (const match of quoted) fragments.push(match[1]);
  const jsxText = line.replace(/<[^>]*>/g, " ").replace(/\{[^}]*\}/g, " ").trim();
  if (
    /[가-힣]/.test(jsxText)
    && !jsxText.includes("$")
    && !/^\s*(const|return|case|throw|private|String|Map|if|for)\b/.test(line)
    && !/^[\w\s.()[\]{}?:,;=+\-*/<>!&|`"']+$/.test(jsxText)
  ) {
    fragments.push(jsxText);
  }
  return [...new Set(fragments.map((s) => s.replace(/\\[nrt]/g, " ").trim()).filter(Boolean))];
}

function isAuditConfigurationLine(line) {
  const trimmed = line.trim();
  return trimmed.startsWith("regex:")
    || trimmed.startsWith("new RegExp(")
    || trimmed.startsWith("[/")
    || trimmed.includes("Pattern.compile(")
    || trimmed.includes("_PATTERN")
    || trimmed.startsWith("`\\\\b(?:");
}

function isInternalPromptTextBlockStart(line) {
  return /private static final String\s+\w*(?:PROMPT|SCHEMA)\s*=\s*"""/.test(line);
}

const findings = [];
for (const target of targets) {
  const path = join(root, target);
  if (!existsSync(path)) continue;
  const lines = readFileSync(path, "utf8").split(/\r?\n/);
  let inInternalTextBlock = false;
  lines.forEach((line, index) => {
    if (isInternalPromptTextBlockStart(line)) {
      inInternalTextBlock = !line.includes('""";');
      return;
    }
    if (inInternalTextBlock) {
      if (line.includes('"""')) {
        inInternalTextBlock = false;
      }
      return;
    }
    for (const fragment of koreanFragments(line)) {
      for (const check of checks) {
        if (check.regex.test(fragment)) {
          findings.push({ file: target, line: index + 1, check: check.id, label: check.label, text: fragment, advice: check.advice });
        }
      }
    }
  });
}

const report = {
  generatedAt: new Date().toISOString(),
  tool: "patina-cli@4.0.0",
  patternCatalog: ".local/patina/node_modules/patina-cli/docs/PATTERNS-KO.md",
  catalogLoaded: patternCatalog.includes("Korean Pattern Reference"),
  mode: "deterministic workspace scan using Patina KO pattern catalog; Patina live rewrite requires PATINA_API_KEY/OPENAI_API_KEY/GEMINI_API_KEY or a working local CLI backend",
  summary: {
    targetFiles: targets.length,
    findings: findings.length,
    byCheck: Object.fromEntries(checks.map((check) => [check.id, findings.filter((finding) => finding.check === check.id).length])),
  },
  findings,
};
const outPath = join(root, ".omx", "artifacts", "patina", "korean-copy-audit.json");
mkdirSync(dirname(outPath), { recursive: true });
writeFileSync(outPath, JSON.stringify(report, null, 2), "utf8");
console.log(`${outPath}`);
console.log(JSON.stringify(report.summary, null, 2));
