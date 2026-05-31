"use client";

import { useEffect, useMemo } from "react";
import { useSearchParams } from "next/navigation";

function getBooleanParam(value: string | null, fallback: boolean) {
  if (value == null) {
    return fallback;
  }

  return value === "1" || value.toLowerCase() === "true";
}

export function DevVisualLoginView() {
  const searchParams = useSearchParams();
  const loginUrl = useMemo(() => {
    const email = searchParams.get("email") ?? `visual-qa-${Date.now()}@time-table.test`;
    const name = searchParams.get("name") ?? "Visual QA Local User";
    const target = searchParams.get("target");
    const params = new URLSearchParams({
      email,
      name,
      connectGoogle: String(getBooleanParam(searchParams.get("connectGoogle"), false)),
      writeCapable: String(getBooleanParam(searchParams.get("writeCapable"), false)),
    });

    if (target) {
      params.set("target", target);
    }

    return `/api/auth/mock/login?${params.toString()}`;
  }, [searchParams]);

  useEffect(() => {
    window.location.assign(loginUrl);
  }, [loginUrl]);

  return (
    <div className="status-screen">
      <div className="status-panel">
        <p className="eyebrow">개발 전용</p>
        <h1>비주얼 검증용 계정을 준비합니다.</h1>
        <p>로컬 개발 서버에서만 Google OAuth 없이 mock 세션으로 이동합니다.</p>
      </div>
    </div>
  );
}
