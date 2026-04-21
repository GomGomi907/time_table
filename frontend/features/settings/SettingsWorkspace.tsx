"use client";

import { Loader2, RefreshCw, Save } from "lucide-react";
import { useMemo, useState } from "react";
import { getApiErrorMessage } from "@/shared/api/getApiErrorMessage";
import type { SettingsResponse, SettingsUpdateRequest } from "@/shared/api/types";
import type { AuthSessionState } from "@/features/auth/useAuthSessionState";
import { useSettings } from "./useSettings";

interface SettingsWorkspaceProps {
  auth: AuthSessionState;
}

const defaultForm: SettingsUpdateRequest = {
  quietHoursStart: "22:00",
  quietHoursEnd: "08:00",
  bufferMinutes: 10,
  overtimeTriggerMinutes: 15,
  openGapTriggerMinutes: 30,
  interventionFrequency: "balanced",
};

const mapSettingsToForm = (data: SettingsResponse): SettingsUpdateRequest => ({
  quietHoursStart: data.quietHoursStart.slice(0, 5),
  quietHoursEnd: data.quietHoursEnd.slice(0, 5),
  bufferMinutes: data.bufferMinutes,
  overtimeTriggerMinutes: data.overtimeTriggerMinutes,
  openGapTriggerMinutes: data.openGapTriggerMinutes,
  interventionFrequency: data.interventionFrequency,
});

export const SettingsWorkspace = ({ auth }: SettingsWorkspaceProps) => {
  const settings = useSettings();
  const [draft, setDraft] = useState<SettingsUpdateRequest | null>(null);
  const [feedback, setFeedback] = useState<string | null>(null);

  const form = useMemo(
    () => draft ?? (settings.data ? mapSettingsToForm(settings.data) : defaultForm),
    [draft, settings.data]
  );

  const updateForm = (patch: Partial<SettingsUpdateRequest>) => {
    setDraft({
      ...form,
      ...patch,
    });
  };

  const saveSettings = async () => {
    try {
      await settings.save(form);
      setFeedback("설정 내용이 안전하게 반영되었습니다.");
    } catch (error) {
      setFeedback(getApiErrorMessage(error, "설정을 저장하지 못했습니다."));
    }
  };

  const resetSettings = async () => {
    setDraft(null);
    setFeedback(null);
    await settings.refresh();
  };

  const isConnected = auth.session?.googleConnectionStatus === "CONNECTED";

  return (
    <div className="space-y-6">
      <section className="surface-card p-6 sm:p-8">
        <div className="flex flex-col gap-6 xl:flex-row xl:items-start xl:justify-between">
          <div className="max-w-3xl space-y-3">
            <p className="metric-label">설정</p>
            <h1 className="text-3xl font-extrabold tracking-tight text-[var(--foreground)] sm:text-4xl">
              시스템 및 운영 설정
            </h1>
            <p className="max-w-2xl text-base leading-7 text-[var(--foreground-muted)]">
              시간표 엔진이 사용하는 기준 시간과 여백 규칙, Google 연동 상태를 한곳에서 관리합니다.
            </p>
          </div>

          <div className="flex flex-wrap gap-3">
            <button type="button" onClick={() => void resetSettings()} className="btn-secondary">
              <RefreshCw className={`h-4 w-4 ${settings.isLoading ? "animate-spin" : ""}`} />
              초기화
            </button>
            <button type="button" onClick={() => void saveSettings()} disabled={settings.isSaving} className="btn-primary">
              {settings.isSaving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
              변경 내용 저장
            </button>
          </div>
        </div>
      </section>

      <div className="grid gap-4 md:grid-cols-3">
        <article className="metric-card">
          <p className="metric-label">Google 연동</p>
          <p className="text-2xl font-extrabold tracking-tight text-[var(--foreground)]">
            {isConnected ? "연결됨" : "미연결"}
          </p>
          <p className="text-sm text-[var(--foreground-muted)]">현재 계정의 캘린더 / 할 일 연동 상태</p>
        </article>
        <article className="metric-card">
          <p className="metric-label">활동 제한 시간</p>
          <p className="text-2xl font-extrabold tracking-tight text-[var(--foreground)]">
            {form.quietHoursStart} ~ {form.quietHoursEnd}
          </p>
          <p className="text-sm text-[var(--foreground-muted)]">이 시간대를 기준으로 일정 배치를 제한합니다.</p>
        </article>
        <article className="metric-card">
          <p className="metric-label">기본 분석 엔진</p>
          <p className="text-2xl font-extrabold tracking-tight text-[var(--foreground)]">Gemma 4.0</p>
          <p className="text-sm text-[var(--foreground-muted)]">로컬 환경에서 일정 정리를 수행합니다.</p>
        </article>
      </div>

      <div className="grid gap-6 xl:grid-cols-[minmax(0,1.1fr)_360px]">
        <section className="surface-card p-6 sm:p-8">
          <div className="space-y-3">
            <p className="metric-label">엔진 설정</p>
            <h2 className="text-2xl font-bold tracking-tight text-[var(--foreground)]">일정 엔진 파라미터</h2>
            <p className="max-w-2xl text-sm leading-6 text-[var(--foreground-muted)]">
              일정 생성과 공백 분석에 쓰이는 기준입니다. 저장하면 이후 계산에 바로 반영됩니다.
            </p>
          </div>

          <div className="mt-6 grid gap-5 md:grid-cols-2">
            <div>
              <label className="field-label" htmlFor="settings-start">
                일과 시작 제한
              </label>
              <input
                id="settings-start"
                type="time"
                className="input-field mt-2"
                value={form.quietHoursStart}
                onChange={(event) => updateForm({ quietHoursStart: event.target.value })}
              />
            </div>

            <div>
              <label className="field-label" htmlFor="settings-end">
                일과 종료 제한
              </label>
              <input
                id="settings-end"
                type="time"
                className="input-field mt-2"
                value={form.quietHoursEnd}
                onChange={(event) => updateForm({ quietHoursEnd: event.target.value })}
              />
            </div>

            <div>
              <label className="field-label" htmlFor="settings-buffer">
                최소 전환 여백 (분)
              </label>
              <input
                id="settings-buffer"
                type="number"
                min="0"
                className="input-field mt-2"
                value={form.bufferMinutes}
                onChange={(event) => updateForm({ bufferMinutes: Number(event.target.value) })}
              />
              <p className="mt-2 text-sm text-[var(--foreground-muted)]">일정 사이에 확보할 최소 이동 / 휴식 시간</p>
            </div>

            <div>
              <label className="field-label" htmlFor="settings-overtime">
                초과 인지 임계치 (분)
              </label>
              <input
                id="settings-overtime"
                type="number"
                min="0"
                className="input-field mt-2"
                value={form.overtimeTriggerMinutes}
                onChange={(event) => updateForm({ overtimeTriggerMinutes: Number(event.target.value) })}
              />
              <p className="mt-2 text-sm text-[var(--foreground-muted)]">계획보다 밀렸다고 판단할 기준 시간</p>
            </div>

            <div>
              <label className="field-label" htmlFor="settings-gap">
                공백 최적화 기준 (분)
              </label>
              <input
                id="settings-gap"
                type="number"
                min="0"
                className="input-field mt-2"
                value={form.openGapTriggerMinutes}
                onChange={(event) => updateForm({ openGapTriggerMinutes: Number(event.target.value) })}
              />
              <p className="mt-2 text-sm text-[var(--foreground-muted)]">재배치를 제안할 큰 공백 기준</p>
            </div>

            <div>
              <label className="field-label" htmlFor="settings-intervention">
                개입 강도
              </label>
              <select
                id="settings-intervention"
                className="select-field mt-2"
                value={form.interventionFrequency}
                onChange={(event) => updateForm({ interventionFrequency: event.target.value })}
              >
                <option value="minimal">보수적 개입</option>
                <option value="balanced">기본 개입</option>
                <option value="active">적극적 개입</option>
              </select>
              <p className="mt-2 text-sm text-[var(--foreground-muted)]">엔진이 일정 재조정에 개입하는 정도</p>
            </div>
          </div>
        </section>

        <aside className="space-y-6">
          <section className="surface-card p-6 sm:p-8">
            <p className="metric-label">연동 상태</p>
            <div className="mt-4 flex items-center gap-3">
              <span className={`h-3 w-3 rounded-full ${isConnected ? "bg-[var(--success)]" : "bg-[var(--error)]"}`} />
              <span className="text-base font-bold text-[var(--foreground)]">
                {isConnected ? "Google 계정 연결됨" : "Google 계정 미연결"}
              </span>
            </div>
            <p className="mt-3 text-sm leading-6 text-[var(--foreground-muted)]">
              {auth.session?.email ?? "로그인이 필요합니다"}
            </p>

            <button type="button" onClick={() => void auth.startGoogleLogin()} className="btn-secondary mt-6 w-full">
              연동 계정 변경
            </button>
          </section>

          <section className="surface-card p-6 sm:p-8">
            <p className="metric-label">저장 결과</p>
            <p className="mt-4 text-sm leading-7 text-[var(--foreground-muted)]">
              {feedback ?? settings.error ?? "설정 저장 결과와 오류 메시지가 이곳에 표시됩니다."}
            </p>
          </section>
        </aside>
      </div>
    </div>
  );
};
