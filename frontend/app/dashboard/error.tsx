"use client";

import { RouteErrorView } from "@/components/route-error-view";

export default function DashboardError({
  error,
  reset,
  unstable_retry,
}: {
  error: Error & { digest?: string };
  reset?: () => void;
  unstable_retry?: () => void;
}) {
  return (
    <RouteErrorView
      error={error}
      reset={reset}
      title="오늘 화면을 불러오지 못했습니다."
      unstable_retry={unstable_retry}
    />
  );
}
