"use client";

import { useEffect } from "react";

interface RouteErrorViewProps {
  error: Error & { digest?: string };
  title: string;
  reset?: () => void;
  unstable_retry?: () => void;
}

export function RouteErrorView({ error, title, reset, unstable_retry }: RouteErrorViewProps) {
  useEffect(() => {
    console.error("Route segment failed", error);
  }, [error]);

  const retry = unstable_retry ?? reset;

  return (
    <div className="status-screen">
      <div className="status-panel" role="alert">
        <p className="eyebrow">화면 오류</p>
        <h1>{title}</h1>
        <p>최신 데이터를 다시 불러오면 복구될 수 있습니다.</p>
        {retry ? (
          <button type="button" className="primary-button" onClick={() => retry()}>
            다시 시도
          </button>
        ) : null}
      </div>
    </div>
  );
}
