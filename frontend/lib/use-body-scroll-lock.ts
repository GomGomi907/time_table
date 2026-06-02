"use client";

import { useEffect, useId } from "react";

import { useAppStore } from "@/stores/app-store";

export function useBodyScrollLock(locked: boolean) {
  const id = useId();
  const acquireBodyScrollLock = useAppStore((state) => state.acquireBodyScrollLock);
  const releaseBodyScrollLock = useAppStore((state) => state.releaseBodyScrollLock);

  useEffect(() => {
    if (!locked) {
      return;
    }

    acquireBodyScrollLock(id);

    return () => {
      releaseBodyScrollLock(id);
    };
  }, [acquireBodyScrollLock, id, locked, releaseBodyScrollLock]);
}
