"use client";

import Link from "next/link";

export interface WorkspaceSuggestion {
  id: string;
  title: string;
  detail: string;
  tone?: "default" | "accent" | "success" | "danger";
  href?: string;
  actionLabel?: string;
}

interface AiSuggestionsWidgetProps {
  title?: string;
  description?: string;
  suggestions: WorkspaceSuggestion[];
}

const toneClassMap: Record<NonNullable<WorkspaceSuggestion["tone"]>, string> = {
  default: "border-[var(--border)] bg-[var(--surface-raised)]",
  accent: "border-[rgba(245,92,18,0.18)] bg-[rgba(245,92,18,0.08)]",
  success: "border-[rgba(33,122,87,0.18)] bg-[rgba(33,122,87,0.08)]",
  danger: "border-[rgba(195,59,67,0.18)] bg-[rgba(195,59,67,0.08)]",
};

export const AiSuggestionsWidget = ({
  title = "AI 제안",
  description = "빈 시간, 충돌 가능성, 동기화 상태를 기준으로 실행 보조 제안을 정리합니다.",
  suggestions,
}: AiSuggestionsWidgetProps) => {
  return (
    <section className="surface-card p-6 sm:p-8">
      <div>
        <p className="metric-label">AI 제안 영역</p>
        <h2 className="mt-2 text-2xl font-bold tracking-tight text-[var(--foreground)]">
          {title}
        </h2>
        <p className="mt-2 max-w-3xl text-sm leading-6 text-[var(--foreground-muted)]">
          {description}
        </p>
      </div>

      <div className="mt-6 grid gap-3 lg:grid-cols-3">
        {suggestions.map((suggestion) => {
          const tone = suggestion.tone ?? "default";

          return (
            <article
              key={suggestion.id}
              className={`rounded-2xl border p-4 ${toneClassMap[tone]}`}
            >
              <h3 className="text-base font-bold text-[var(--foreground)]">
                {suggestion.title}
              </h3>
              <p className="mt-2 text-sm leading-6 text-[var(--foreground-muted)]">
                {suggestion.detail}
              </p>

              {suggestion.href && suggestion.actionLabel ? (
                <Link
                  href={suggestion.href}
                  className="btn-ghost mt-4 justify-start border border-[var(--border)]"
                >
                  {suggestion.actionLabel}
                </Link>
              ) : null}
            </article>
          );
        })}
      </div>
    </section>
  );
};
