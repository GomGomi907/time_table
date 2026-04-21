"use client";

import type { ChangeEvent, ReactNode } from "react";
import { useMemo, useState } from "react";
import { motion } from "framer-motion";
import {
  Bell,
  BookOpen,
  Briefcase,
  CalendarDays,
  CheckCircle2,
  ChevronLeft,
  Circle,
  Clock3,
  Coffee,
  Dumbbell,
  LayoutDashboard,
  MoreHorizontal,
  Moon,
  Plus,
  Search,
  Sun,
} from "lucide-react";

const START_HOUR = 8;
const END_HOUR = 22;
const SLOT_HEIGHT = 64;
const hours = Array.from({ length: END_HOUR - START_HOUR + 1 }, (_, index) => index + START_HOUR);

const days = [
  { key: "Mon", label: "Mon", date: "21" },
  { key: "Tue", label: "Tue", date: "22" },
  { key: "Wed", label: "Wed", date: "23" },
  { key: "Thu", label: "Thu", date: "24" },
  { key: "Fri", label: "Fri", date: "25" },
  { key: "Sat", label: "Sat", date: "26" },
  { key: "Sun", label: "Sun", date: "27" },
] as const;

const schedule = [
  { title: "캡스톤 작업", day: "Mon", start: 10, end: 12, type: "project", detail: "드론 제어 로직 정리" },
  { title: "수업", day: "Mon", start: 13, end: 15, type: "class", detail: "오토마타와 컴파일러" },
  { title: "운동", day: "Mon", start: 19, end: 20, type: "life", detail: "헬스장" },
  { title: "회의", day: "Tue", start: 9, end: 10, type: "work", detail: "주간 진행 점검" },
  { title: "개발", day: "Tue", start: 14, end: 17, type: "work", detail: "실시간 처리 루프" },
  { title: "스터디", day: "Wed", start: 11, end: 13, type: "class", detail: "논문/기술 스터디" },
  { title: "집중 작업", day: "Thu", start: 9, end: 12, type: "project", detail: "시간표 서비스 설계" },
  { title: "세미나 준비", day: "Thu", start: 14, end: 16, type: "project", detail: "발표 구조 정리" },
  { title: "알바", day: "Sat", start: 12, end: 18, type: "life", detail: "주말 근무" },
  { title: "리뷰/정리", day: "Sun", start: 20, end: 22, type: "project", detail: "주간 회고" },
] as const;

const todayPlan = [
  { time: "09:00", title: "메일 / 공지 확인", meta: "10분 내 처리", icon: Bell },
  { time: "10:00", title: "데이터 파이프라인 점검", meta: "핵심 업무", icon: Briefcase },
  { time: "13:00", title: "졸업작품 설계 정리", meta: "집중 블록", icon: BookOpen },
  { time: "18:30", title: "저녁 / 이동", meta: "휴식", icon: Coffee },
  { time: "20:00", title: "운동", meta: "루틴", icon: Dumbbell },
] as const;

const tasks = [
  { done: true, title: "주간 시간표 초안 정리", tag: "완료" },
  { done: false, title: "반복 일정 편집 UX 정리", tag: "중요" },
  { done: false, title: "오늘 패널 모바일 버전 설계", tag: "설계" },
  { done: false, title: "팀 공유 권한 정책 정의", tag: "정책" },
] as const;

type ViewMode = "week" | "focus";

type CardProps = {
  className?: string;
  children: ReactNode;
};

type ButtonProps = {
  className?: string;
  children: ReactNode;
  type?: "button" | "submit";
  onClick?: () => void;
};

type InputProps = {
  className?: string;
  placeholder?: string;
  value?: string;
  onChange?: (event: ChangeEvent<HTMLInputElement>) => void;
};

type BadgeProps = {
  className?: string;
  children: ReactNode;
};

type ProgressProps = {
  className?: string;
  value: number;
};

const Card = ({ className = "", children }: CardProps) => <section className={className}>{children}</section>;

const CardHeader = ({ className = "", children }: CardProps) => <div className={className}>{children}</div>;

const CardContent = ({ className = "", children }: CardProps) => <div className={className}>{children}</div>;

const CardTitle = ({ className = "", children }: CardProps) => <h2 className={className}>{children}</h2>;

const CardDescription = ({ className = "", children }: CardProps) => <p className={className}>{children}</p>;

const Button = ({ className = "", children, type = "button", onClick }: ButtonProps) => (
  <button type={type} onClick={onClick} className={className}>
    {children}
  </button>
);

const Input = ({ className = "", placeholder, value, onChange }: InputProps) => (
  <input className={className} placeholder={placeholder} value={value} onChange={onChange} />
);

const Badge = ({ className = "", children }: BadgeProps) => <span className={className}>{children}</span>;

const Progress = ({ className = "", value }: ProgressProps) => (
  <div className={`h-2 w-full overflow-hidden rounded-full bg-slate-200 dark:bg-slate-800 ${className}`}>
    <div className="h-full rounded-full bg-indigo-500 transition-all duration-300" style={{ width: `${value}%` }} />
  </div>
);

function pageBackground(isDark: boolean) {
  return isDark
    ? "min-h-screen bg-[linear-gradient(180deg,#020617_0%,#0f172a_45%,#111827_100%)] text-slate-100"
    : "min-h-screen bg-[linear-gradient(180deg,#f8fafc_0%,#f8fafc_45%,#f1f5f9_100%)] text-slate-950";
}

function surfaceCard(isDark: boolean, extra = "") {
  return isDark
    ? `rounded-2xl border border-slate-800/90 bg-slate-900/78 shadow-[0_12px_32px_rgba(2,6,23,0.45)] backdrop-blur ${extra}`
    : `rounded-2xl border border-slate-200/80 bg-white/90 shadow-[0_8px_24px_rgba(15,23,42,0.04)] backdrop-blur ${extra}`;
}

function strongText(isDark: boolean) {
  return isDark ? "text-slate-100" : "text-slate-950";
}

function softText(isDark: boolean) {
  return isDark ? "text-slate-400" : "text-slate-500";
}

function borderTone(isDark: boolean) {
  return isDark ? "border-slate-800" : "border-slate-200";
}

function panelSurface(isDark: boolean) {
  return isDark ? "bg-slate-950/50" : "bg-white";
}

function subtleSurface(isDark: boolean) {
  return isDark ? "bg-slate-900/70" : "bg-slate-50/80";
}

function highlightSurface(isDark: boolean) {
  return isDark ? "bg-indigo-500/10" : "bg-indigo-50/70";
}

function typeStyle(type: string, isDark: boolean) {
  if (isDark) {
    switch (type) {
      case "project":
        return "border-indigo-500/40 bg-indigo-500/12 text-indigo-100";
      case "class":
        return "border-amber-500/35 bg-amber-500/12 text-amber-100";
      case "work":
        return "border-sky-500/35 bg-sky-500/12 text-sky-100";
      default:
        return "border-emerald-500/35 bg-emerald-500/12 text-emerald-100";
    }
  }

  switch (type) {
    case "project":
      return "border-indigo-200 bg-indigo-50 text-indigo-950";
    case "class":
      return "border-amber-200 bg-amber-50 text-amber-950";
    case "work":
      return "border-sky-200 bg-sky-50 text-sky-950";
    default:
      return "border-emerald-200 bg-emerald-50 text-emerald-950";
  }
}

function iconWrap(type: string, isDark: boolean) {
  if (isDark) {
    switch (type) {
      case "project":
        return "bg-indigo-500/16 text-indigo-300";
      case "class":
        return "bg-amber-500/16 text-amber-300";
      case "work":
        return "bg-sky-500/16 text-sky-300";
      default:
        return "bg-emerald-500/16 text-emerald-300";
    }
  }

  switch (type) {
    case "project":
      return "bg-indigo-100 text-indigo-700";
    case "class":
      return "bg-amber-100 text-amber-700";
    case "work":
      return "bg-sky-100 text-sky-700";
    default:
      return "bg-emerald-100 text-emerald-700";
  }
}

function primaryButton(isDark: boolean) {
  return isDark
    ? "rounded-xl bg-indigo-500 text-white hover:bg-indigo-400"
    : "rounded-xl bg-indigo-600 text-white hover:bg-indigo-700";
}

function secondaryButton(isDark: boolean) {
  return isDark
    ? "rounded-xl border-slate-700 text-slate-200 hover:bg-slate-800"
    : "rounded-xl border-slate-200 text-slate-700 hover:bg-slate-50";
}

function dayEvents(dayKey: string) {
  return schedule.filter((item) => item.day === dayKey);
}

function eventTop(start: number) {
  return (start - START_HOUR) * SLOT_HEIGHT + 4;
}

function eventHeight(start: number, end: number) {
  return (end - start) * SLOT_HEIGHT - 8;
}

function StatCard({
  label,
  value,
  desc,
  isDark,
}: {
  label: string;
  value: string;
  desc: string;
  isDark: boolean;
}) {
  return (
    <div className={surfaceCard(isDark, "p-4")}>
      <div className={`text-sm ${softText(isDark)}`}>{label}</div>
      <div className={`mt-1 text-2xl font-semibold tracking-tight ${strongText(isDark)}`}>{value}</div>
      <div className={`mt-1 text-xs ${softText(isDark)}`}>{desc}</div>
    </div>
  );
}

function ThemeToggle({
  isDark,
  setIsDark,
}: {
  isDark: boolean;
  setIsDark: (value: boolean) => void;
}) {
  return (
    <button
      onClick={() => setIsDark(!isDark)}
      className={`inline-flex items-center gap-2 rounded-xl border px-3 py-2 text-sm font-medium transition ${
        isDark
          ? "border-slate-700 bg-slate-900/70 text-slate-100 hover:bg-slate-800"
          : "border-slate-200 bg-white/90 text-slate-700 hover:bg-slate-50"
      }`}
    >
      {isDark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
      {isDark ? "라이트" : "다크"}
    </button>
  );
}

function TopNavigation({
  currentView,
  setCurrentView,
  isDark,
  setIsDark,
}: {
  currentView: ViewMode;
  setCurrentView: (value: ViewMode) => void;
  isDark: boolean;
  setIsDark: (value: boolean) => void;
}) {
  return (
    <div className="mb-8 flex flex-col gap-4 xl:flex-row xl:items-end xl:justify-between">
      <div>
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <Badge
            className={`${
              isDark
                ? "border-indigo-500/30 bg-indigo-500/12 text-indigo-200"
                : "border-indigo-200 bg-indigo-50 text-indigo-700"
            } rounded-full px-3 py-1 hover:bg-inherit`}
          >
            Separated Views
          </Badge>
          <Badge
            className={`${
              isDark
                ? "border-slate-700 bg-slate-900/70 text-slate-300"
                : "border-slate-200 bg-white/80 text-slate-600"
            } rounded-full px-3 py-1`}
          >
            Weekly + Focus
          </Badge>
        </div>
        <h1 className={`text-3xl font-semibold tracking-tight md:text-4xl ${strongText(isDark)}`}>시간표 웹서비스 화면 구조</h1>
        <p className={`mt-3 max-w-4xl text-sm leading-6 md:text-base ${softText(isDark)}`}>
          주간 시간표는 계획과 편집 중심 화면으로, 오늘/액션 패널은 실행 중심 화면으로 분리했습니다. 한 화면에서 두 성격을
          섞지 않고, 사용 목적에 따라 뷰를 전환하는 구조입니다.
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <ThemeToggle isDark={isDark} setIsDark={setIsDark} />
        <div className={surfaceCard(isDark, "inline-flex p-1")}>
          <button
            onClick={() => setCurrentView("week")}
            className={`inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium transition ${
              currentView === "week"
                ? isDark
                  ? "bg-indigo-500 text-white shadow-sm"
                  : "bg-indigo-600 text-white shadow-sm"
                : isDark
                  ? "text-slate-300 hover:bg-slate-800"
                  : "text-slate-600 hover:bg-slate-100"
            }`}
          >
            <CalendarDays className="h-4 w-4" />
            주간 화면
          </button>
          <button
            onClick={() => setCurrentView("focus")}
            className={`inline-flex items-center gap-2 rounded-xl px-4 py-2 text-sm font-medium transition ${
              currentView === "focus"
                ? isDark
                  ? "bg-indigo-500 text-white shadow-sm"
                  : "bg-indigo-600 text-white shadow-sm"
                : isDark
                  ? "text-slate-300 hover:bg-slate-800"
                  : "text-slate-600 hover:bg-slate-100"
            }`}
          >
            <LayoutDashboard className="h-4 w-4" />
            오늘 화면
          </button>
        </div>
      </div>
    </div>
  );
}

function WeekGrid({ isDark }: { isDark: boolean }) {
  return (
    <div className={`overflow-hidden rounded-2xl border ${borderTone(isDark)} ${panelSurface(isDark)}`}>
      <div className="grid grid-cols-[72px_repeat(7,minmax(120px,1fr))]">
        <div className={`border-b border-r p-3 text-sm font-medium ${borderTone(isDark)} ${softText(isDark)} ${subtleSurface(isDark)}`}>Time</div>
        {days.map((day) => (
          <div
            key={day.key}
            className={`border-b border-r p-3 last:border-r-0 ${borderTone(isDark)} ${
              day.key === "Thu" ? highlightSurface(isDark) : subtleSurface(isDark)
            }`}
          >
            <div className={`text-center text-sm font-semibold ${strongText(isDark)}`}>{day.label}</div>
            <div className={`mt-1 text-center text-xs ${softText(isDark)}`}>Apr {day.date}</div>
          </div>
        ))}

        <div className={`relative border-r ${borderTone(isDark)}`} style={{ height: `${hours.length * SLOT_HEIGHT}px` }}>
          {hours.map((hour, index) => (
            <div
              key={hour}
              className={`flex h-16 items-start px-3 py-2 text-xs ${softText(isDark)} ${
                index !== hours.length - 1 ? `border-b ${borderTone(isDark)}` : ""
              }`}
            >
              {String(hour).padStart(2, "0")}:00
            </div>
          ))}
        </div>

        {days.map((day) => (
          <div
            key={`column-${day.key}`}
            className={`relative border-r last:border-r-0 ${borderTone(isDark)} ${
              day.key === "Thu" ? (isDark ? "bg-indigo-500/5" : "bg-indigo-50/20") : panelSurface(isDark)
            }`}
            style={{ height: `${hours.length * SLOT_HEIGHT}px` }}
          >
            <div className="absolute inset-0">
              {hours.map((hour, index) => (
                <div key={`${day.key}-${hour}`} className={`h-16 ${index !== hours.length - 1 ? `border-b ${borderTone(isDark)}` : ""}`} />
              ))}
            </div>

            <div className="relative h-full">
              {dayEvents(day.key).map((item) => (
                <motion.div
                  key={`${item.day}-${item.start}-${item.title}`}
                  initial={{ opacity: 0, y: 6 }}
                  animate={{ opacity: 1, y: 0 }}
                  className={`absolute left-1 right-1 rounded-2xl border p-3 text-xs shadow-[0_6px_18px_rgba(15,23,42,0.08)] ${typeStyle(item.type, isDark)}`}
                  style={{
                    top: `${eventTop(item.start)}px`,
                    height: `${eventHeight(item.start, item.end)}px`,
                  }}
                >
                  <div className="flex items-start justify-between gap-2">
                    <div className="font-semibold leading-5">{item.title}</div>
                    <div className={`rounded-lg p-1 ${iconWrap(item.type, isDark)}`}>
                      <MoreHorizontal className="h-3.5 w-3.5" />
                    </div>
                  </div>
                  <div className="mt-1 opacity-75">
                    {item.start}:00 - {item.end}:00
                  </div>
                  <div className="mt-2 line-clamp-3 opacity-80">{item.detail}</div>
                </motion.div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function WeekView({
  query,
  setQuery,
  quickStats,
  filtered,
  isDark,
}: {
  query: string;
  setQuery: (value: string) => void;
  quickStats: Array<{ label: string; value: string; desc: string }>;
  filtered: Array<{ label: string; value: string; desc: string }>;
  isDark: boolean;
}) {
  return (
    <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} className="space-y-6">
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-[1fr_360px]">
        <div className="grid gap-4 md:grid-cols-2 xl:col-span-1 xl:grid-cols-4">
          {(filtered.length ? filtered : quickStats).map((item) => (
            <StatCard key={item.label} label={item.label} value={item.value} desc={item.desc} isDark={isDark} />
          ))}
        </div>
        <Card className={surfaceCard(isDark)}>
          <CardContent className="flex h-full flex-col justify-center gap-3 p-5">
            <div className="relative">
              <Search className={`absolute left-3 top-3 h-4 w-4 ${isDark ? "text-slate-500" : "text-slate-400"}`} />
              <Input
                value={query}
                onChange={(event) => setQuery(event.target.value)}
                placeholder="지표나 패널 탐색"
                className={`w-full rounded-xl border px-3 py-2 pl-9 ${
                  isDark
                    ? "border-slate-700 bg-slate-950/60 text-slate-100 placeholder:text-slate-500"
                    : "border-slate-200 bg-white text-slate-900 placeholder:text-slate-400"
                }`}
              />
            </div>
            <div className="flex flex-wrap gap-2">
              <Button className={`inline-flex items-center gap-2 border px-4 py-2 ${secondaryButton(isDark)}`}>
                <CalendarDays className="h-4 w-4" />
                월 보기
              </Button>
              <Button className={`inline-flex items-center gap-2 px-4 py-2 ${primaryButton(isDark)}`}>
                <Plus className="h-4 w-4" />
                새 일정
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>

      <Card className={surfaceCard(isDark)}>
        <CardHeader className="pb-4">
          <div className="flex flex-col gap-4 xl:flex-row xl:items-center xl:justify-between">
            <div>
              <CardTitle className={`text-xl ${strongText(isDark)}`}>주간 시간표</CardTitle>
              <CardDescription className={softText(isDark)}>60분 단위 고정 슬롯 기준으로 정렬한 메인 화면입니다.</CardDescription>
            </div>
            <div className="flex flex-wrap items-center gap-2">
              <Button className={`inline-flex items-center gap-2 border px-4 py-2 text-sm ${secondaryButton(isDark)}`}>
                <ChevronLeft className="h-4 w-4" />
                이전 주
              </Button>
              <Button className={`inline-flex items-center gap-2 px-4 py-2 text-sm ${primaryButton(isDark)}`}>이번 주</Button>
              <Button className={`inline-flex items-center gap-2 border px-4 py-2 text-sm ${secondaryButton(isDark)}`}>반복 일정</Button>
              <Button className={`inline-flex items-center gap-2 border px-4 py-2 text-sm ${secondaryButton(isDark)}`}>
                <Plus className="h-4 w-4" />
                추가
              </Button>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          <WeekGrid isDark={isDark} />
        </CardContent>
      </Card>
    </motion.div>
  );
}

function TodayTimeline({ isDark }: { isDark: boolean }) {
  return (
    <Card className={surfaceCard(isDark)}>
      <CardHeader className="pb-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <CardTitle className={`text-lg ${strongText(isDark)}`}>오늘 실행</CardTitle>
            <CardDescription className={softText(isDark)}>지금 해야 할 흐름을 짧게 보는 패널</CardDescription>
          </div>
          <Badge
            className={`${
              isDark ? "border-sky-500/30 bg-sky-500/12 text-sky-200" : "border-sky-200 bg-sky-50 text-sky-700"
            } rounded-full px-3 py-1 hover:bg-inherit`}
          >
            {todayPlan.length} events
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {todayPlan.map((item, index) => {
          const Icon = item.icon;
          const isFocus = index === 2;
          return (
            <div
              key={item.time}
              className={`relative flex items-start gap-3 rounded-2xl border p-3 ${
                isFocus
                  ? isDark
                    ? "border-indigo-500/30 bg-indigo-500/10"
                    : "border-indigo-200 bg-indigo-50/70"
                  : isDark
                    ? "border-slate-800 bg-slate-950/45"
                    : "border-slate-200 bg-white"
              }`}
            >
              {isFocus && <div className={`absolute inset-y-0 left-0 w-1 rounded-l-2xl ${isDark ? "bg-indigo-400" : "bg-indigo-500"}`} />}
              <div
                className={`rounded-xl p-2 ${
                  isFocus
                    ? isDark
                      ? "bg-indigo-500/16 text-indigo-300"
                      : "bg-indigo-100 text-indigo-700"
                    : isDark
                      ? "bg-slate-800 text-slate-300"
                      : "bg-slate-100 text-slate-600"
                }`}
              >
                <Icon className="h-4 w-4" />
              </div>
              <div className="min-w-0 flex-1 pl-1">
                <div className="flex items-center justify-between gap-3">
                  <div className={`text-sm font-semibold ${strongText(isDark)}`}>{item.title}</div>
                  <div className={`text-xs ${softText(isDark)}`}>{item.time}</div>
                </div>
                <div className={`mt-1 text-xs ${softText(isDark)}`}>{item.meta}</div>
              </div>
            </div>
          );
        })}
      </CardContent>
    </Card>
  );
}

function TaskPanel({ isDark }: { isDark: boolean }) {
  const remaining = tasks.filter((task) => !task.done).length;
  return (
    <Card className={surfaceCard(isDark)}>
      <CardHeader className="pb-4">
        <div className="flex items-start justify-between gap-3">
          <div>
            <CardTitle className={`text-lg ${strongText(isDark)}`}>오늘의 액션</CardTitle>
            <CardDescription className={softText(isDark)}>시간표와 연결되는 실행 태스크</CardDescription>
          </div>
          <Badge
            className={`${
              isDark ? "border-slate-700 bg-slate-900/70 text-slate-300" : "border-slate-200 bg-slate-50 text-slate-600"
            } rounded-full px-3 py-1`}
          >
            {remaining} left
          </Badge>
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        {tasks.map((task) => (
          <div
            key={task.title}
            className={`flex items-center gap-3 rounded-2xl border p-3 ${borderTone(isDark)} ${isDark ? "bg-slate-950/45" : "bg-white"}`}
          >
            {task.done ? (
              <CheckCircle2 className={`h-5 w-5 ${isDark ? "text-emerald-400" : "text-emerald-600"}`} />
            ) : (
              <Circle className={`h-5 w-5 ${isDark ? "text-slate-600" : "text-slate-400"}`} />
            )}
            <div className="min-w-0 flex-1">
              <div
                className={`text-sm font-medium ${
                  task.done
                    ? isDark
                      ? "text-slate-500 line-through"
                      : "text-slate-400 line-through"
                    : strongText(isDark)
                }`}
              >
                {task.title}
              </div>
            </div>
            <Badge
              className={`${
                isDark ? "border-slate-700 bg-slate-900/70 text-slate-300" : "border-slate-200 bg-slate-50 text-slate-600"
              } rounded-full px-3 py-1 text-[11px]`}
            >
              {task.tag}
            </Badge>
          </div>
        ))}
        <div
          className={`rounded-2xl border p-4 ${
            isDark
              ? "border-indigo-500/20 bg-gradient-to-r from-indigo-500/10 to-sky-500/10"
              : "border-indigo-100 bg-gradient-to-r from-indigo-50 to-sky-50"
          }`}
        >
          <div className={`text-sm ${softText(isDark)}`}>Daily Score</div>
          <div className={`mt-1 text-lg font-semibold ${strongText(isDark)}`}>실행률 62%</div>
          <Progress value={62} className="mt-3" />
        </div>
      </CardContent>
    </Card>
  );
}

function FocusView({ isDark }: { isDark: boolean }) {
  return (
    <motion.div initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} className="space-y-6">
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <StatCard label="오늘 일정" value="5개" desc="당일 실행 기준" isDark={isDark} />
        <StatCard label="남은 액션" value="3개" desc="우선순위 태스크" isDark={isDark} />
        <StatCard label="집중 시간" value="4h" desc="오늘 확보한 블록" isDark={isDark} />
        <StatCard label="실행률" value="62%" desc="완료율 기준" isDark={isDark} />
      </div>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_420px]">
        <Card className={surfaceCard(isDark)}>
          <CardHeader className="pb-4">
            <div className="flex flex-col gap-4 lg:flex-row lg:items-center lg:justify-between">
              <div>
                <CardTitle className={`text-xl ${strongText(isDark)}`}>오늘 화면</CardTitle>
                <CardDescription className={softText(isDark)}>실행과 체크에 집중한 독립 화면입니다.</CardDescription>
              </div>
              <div className="flex flex-wrap gap-2">
                <Button className={`inline-flex items-center gap-2 border px-4 py-2 ${secondaryButton(isDark)}`}>
                  <Clock3 className="h-4 w-4" />
                  타임라인
                </Button>
                <Button className={`inline-flex items-center gap-2 px-4 py-2 ${primaryButton(isDark)}`}>
                  <Plus className="h-4 w-4" />
                  빠른 추가
                </Button>
              </div>
            </div>
          </CardHeader>
          <CardContent>
            <div className="space-y-4">
              {Array.from({ length: 12 }, (_, index) => index + 8).map((hour, idx) => {
                const match = todayPlan.find((item) => Number(item.time.split(":")[0]) === hour);
                return (
                  <div key={hour} className="grid grid-cols-[72px_1fr] gap-3">
                    <div className={`pt-1 text-sm ${softText(isDark)}`}>{hour}:00</div>
                    <div
                      className={`relative min-h-[80px] rounded-2xl border p-4 ${borderTone(isDark)} ${
                        isDark ? "bg-slate-950/45" : "bg-white"
                      }`}
                    >
                      {idx === 5 && <div className={`absolute inset-y-0 left-0 w-1 rounded-l-2xl ${isDark ? "bg-indigo-400" : "bg-indigo-500"}`} />}
                      {match ? (
                        <motion.div initial={{ opacity: 0, y: 6 }} animate={{ opacity: 1, y: 0 }} className="pl-1">
                          <div className="flex items-start justify-between gap-3">
                            <div>
                              <div className={`text-base font-semibold ${strongText(isDark)}`}>{match.title}</div>
                              <div className={`mt-1 text-sm ${softText(isDark)}`}>{match.meta}</div>
                            </div>
                            <Badge
                              className={`${
                                isDark
                                  ? "border-indigo-500/30 bg-indigo-500/12 text-indigo-200"
                                  : "border-indigo-200 bg-indigo-50 text-indigo-700"
                              } rounded-full px-3 py-1`}
                            >
                              {match.time}
                            </Badge>
                          </div>
                        </motion.div>
                      ) : (
                        <div className={`text-sm ${isDark ? "text-slate-600" : "text-slate-400"}`}>빈 슬롯</div>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </CardContent>
        </Card>

        <div className="space-y-6">
          <TodayTimeline isDark={isDark} />
          <TaskPanel isDark={isDark} />
        </div>
      </div>
    </motion.div>
  );
}

export const TodayWorkspaceMockup = () => {
  const [query, setQuery] = useState("");
  const [currentView, setCurrentView] = useState<ViewMode>("week");
  const [isDark, setIsDark] = useState(false);

  const quickStats = useMemo(
    () => [
      { label: "이번 주 일정", value: "18개", desc: "수업/업무/개인 일정 포함" },
      { label: "집중 블록", value: "7시간", desc: "고정 Deep Work 시간" },
      { label: "남은 태스크", value: "3개", desc: "오늘 기준 액션 아이템" },
      { label: "반복 일정", value: "5개", desc: "주간 자동 생성 항목" },
    ],
    []
  );

  const filtered = quickStats.filter((item) => `${item.label} ${item.desc} ${item.value}`.includes(query));

  return (
    <div className={pageBackground(isDark)}>
      <div className="mx-auto max-w-[1600px] px-6 py-8">
        <TopNavigation currentView={currentView} setCurrentView={setCurrentView} isDark={isDark} setIsDark={setIsDark} />
        {currentView === "week" ? (
          <WeekView query={query} setQuery={setQuery} quickStats={quickStats} filtered={filtered} isDark={isDark} />
        ) : (
          <FocusView isDark={isDark} />
        )}
      </div>
    </div>
  );
};
