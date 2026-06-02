"use client";

import { useCallback, useEffect, useState } from "react";

import { formatClockValue } from "@/lib/format";

function isAbsoluteDateTime(value: string | null | undefined) {
  return Boolean(value && !/^\d{2}:\d{2}/.test(value));
}

export function useClientClockFormat() {
  const [mounted, setMounted] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  return useCallback((value: string | null | undefined, timeZone?: string) => {
    if (isAbsoluteDateTime(value) && timeZone && !mounted) {
      return "--:--";
    }
    return formatClockValue(value, timeZone);
  }, [mounted]);
}
