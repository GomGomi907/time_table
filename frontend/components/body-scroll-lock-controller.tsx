"use client";

import { useEffect, useRef } from "react";

import { useAppStore } from "@/stores/app-store";

export function BodyScrollLockController() {
  const lockCount = useAppStore((state) => state.bodyScrollLockCount);
  const previousOverflow = useRef<string | null>(null);

  useEffect(() => {
    if (lockCount > 0) {
      if (previousOverflow.current === null) {
        previousOverflow.current = document.body.style.overflow;
      }
      document.body.style.overflow = "hidden";
      return;
    }

    if (previousOverflow.current !== null) {
      document.body.style.overflow = previousOverflow.current;
      previousOverflow.current = null;
    }
  }, [lockCount]);

  useEffect(() => () => {
    if (previousOverflow.current !== null) {
      document.body.style.overflow = previousOverflow.current;
      previousOverflow.current = null;
    }
  }, []);

  return null;
}
