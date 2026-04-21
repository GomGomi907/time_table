"use client";

import type { ReactNode } from "react";

type WorkspaceStatTone = "default" | "accent" | "success" | "danger";

interface WorkspaceStatProps {
  label: string;
  value: string;
  description: string;
  icon?: ReactNode;
  tone?: WorkspaceStatTone;
}

const toneStyles: Record<WorkspaceStatTone, string> = {
  default:
    "border-[var(--border)] bg-[var(--surface-raised)] text-[var(--foreground)]",
  accent:
    "border-[rgba(245,92,18,0.16)] bg-[rgba(245,92,18,0.08)] text-[var(--foreground)]",
  success:
    "border-[rgba(33,122,87,0.18)] bg-[rgba(33,122,87,0.08)] text-[var(--foreground)]",
  danger:
    "border-[rgba(195,59,67,0.18)] bg-[rgba(195,59,67,0.08)] text-[var(--foreground)]",
};

export const WorkspaceStat = ({
  label,
  value,
  description,
  icon,
  tone = "default",
}: WorkspaceStatProps) => {
  return (
    <article
      className={`rounded-[1.35rem] border p-5 shadow-[var(--shadow-soft)] ${toneStyles[tone]}`}
    >
      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="metric-label">{label}</p>
          <p className="mt-2 text-3xl font-extrabold tracking-tight text-[var(--foreground)]">
            {value}
          </p>
        </div>
        {icon ? (
          <div className="flex h-11 w-11 items-center justify-center rounded-2xl bg-[var(--surface)] text-[var(--accent)]">
            {icon}
          </div>
        ) : null}
      </div>

      <p className="mt-3 text-sm leading-6 text-[var(--foreground-muted)]">
        {description}
      </p>
    </article>
  );
};
