"use client";

import { useEffect } from "react";

import { useAppStore } from "@/stores/app-store";

export function NoticeCenter() {
  const notice = useAppStore((state) => state.notice);
  const clearNotice = useAppStore((state) => state.clearNotice);

  useEffect(() => {
    if (!notice) {
      return;
    }

    const timer = window.setTimeout(() => {
      clearNotice();
    }, 4200);

    return () => {
      window.clearTimeout(timer);
    };
  }, [notice, clearNotice]);

  if (!notice) {
    return null;
  }

  return (
    <div className="notice-center" role="status" aria-live="polite">
      <div className={`notice-card ${notice.tone}`}>
        <strong>{notice.title}</strong>
        {notice.detail ? <p>{notice.detail}</p> : null}
      </div>
    </div>
  );
}
