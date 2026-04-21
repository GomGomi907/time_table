"use client";

import type { ReactNode } from "react";

interface PageIntroProps {
  eyebrow: string;
  title: string;
  description: string;
  actions?: ReactNode;
  meta?: ReactNode;
}

export const PageIntro = ({
  eyebrow,
  title,
  description,
  actions,
  meta,
}: PageIntroProps) => {
  return (
    <section className="surface-card overflow-hidden p-6 sm:p-8">
      <div className="relative">
        <div className="pointer-events-none absolute -right-12 -top-10 h-40 w-40 rounded-full bg-[radial-gradient(circle,rgba(245,92,18,0.16),transparent_68%)]" />
        <div className="pointer-events-none absolute bottom-[-4.5rem] left-[-2rem] h-32 w-32 rounded-full bg-[radial-gradient(circle,rgba(33,122,87,0.12),transparent_68%)]" />

        <div className="relative flex flex-col gap-6 xl:flex-row xl:items-start xl:justify-between">
          <div className="max-w-3xl space-y-3">
            <p className="metric-label">{eyebrow}</p>
            <h1 className="text-3xl font-extrabold tracking-tight text-[var(--foreground)] sm:text-[3.2rem]">
              {title}
            </h1>
            <p className="max-w-2xl text-base leading-7 text-[var(--foreground-muted)]">
              {description}
            </p>
            {meta ? <div className="flex flex-wrap gap-2 pt-2">{meta}</div> : null}
          </div>

          {actions ? <div className="flex flex-wrap gap-3 xl:justify-end">{actions}</div> : null}
        </div>
      </div>
    </section>
  );
};
