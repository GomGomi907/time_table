"use client";

import { useEffect, useState } from "react";

import { useAppStore } from "@/stores/app-store";

export function NoticeCenter() {
  const notice = useAppStore((state) => state.notice);
  const clearNotice = useAppStore((state) => state.clearNotice);
  const [isLeaving, setIsLeaving] = useState(false);

  useEffect(() => {
    if (!notice) {
      setIsLeaving(false);
      return;
    }

    setIsLeaving(false);
    const leaveTimer = window.setTimeout(() => {
      setIsLeaving(true);
    }, 3800);
    const clearTimer = window.setTimeout(() => {
      clearNotice();
    }, 4200);

    return () => {
      window.clearTimeout(leaveTimer);
      window.clearTimeout(clearTimer);
    };
  }, [notice, clearNotice]);

  if (!notice) {
    return null;
  }

  return (
    <div className="notice-center" role="status" aria-live="polite">
      <div className={`notice-card ${notice.tone} ${isLeaving ? "leaving" : ""}`}>
        <strong>{notice.title}</strong>
        {notice.detail ? <p>{notice.detail}</p> : null}
      </div>
    </div>
  );
}
