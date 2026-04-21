"use client";

import { Moon, Sun } from "lucide-react";
import { useEffect, useState } from "react";
import { useHydrated } from "@/shared/lib/useHydrated";

type ThemeMode = "light" | "dark";

const STORAGE_KEY = "tt-theme";

const applyTheme = (theme: ThemeMode) => {
  document.documentElement.dataset.theme = theme;
};

const readThemePreference = (): ThemeMode => {
  if (typeof window === "undefined") {
    return "light";
  }

  const stored = window.localStorage.getItem(STORAGE_KEY);
  if (stored === "dark" || stored === "light") {
    return stored;
  }

  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
};

export const ThemeToggle = () => {
  const hydrated = useHydrated();
  const [theme, setTheme] = useState<ThemeMode>(() => readThemePreference());

  useEffect(() => {
    if (!hydrated) {
      return;
    }

    applyTheme(theme);
  }, [hydrated, theme]);

  if (!hydrated) {
    return (
      <button type="button" className="icon-button" aria-label="테마 전환 준비 중">
        <Sun className="h-4 w-4" />
      </button>
    );
  }

  const nextTheme = theme === "dark" ? "light" : "dark";

  return (
    <button
      type="button"
      className="icon-button"
      onClick={() => {
        setTheme(nextTheme);
        window.localStorage.setItem(STORAGE_KEY, nextTheme);
        applyTheme(nextTheme);
      }}
      aria-label={theme === "dark" ? "라이트 테마로 전환" : "다크 테마로 전환"}
      title={theme === "dark" ? "라이트 테마" : "다크 테마"}
    >
      {theme === "dark" ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
    </button>
  );
};
