"use client";

import { AuthenticatedAppPage } from "@/components/auth/AuthenticatedAppPage";
import { ScheduleWorkspace } from "@/features/schedule/ScheduleWorkspace";

export default function SchedulePage() {
  return (
    <AuthenticatedAppPage>
      {() => <ScheduleWorkspace />}
    </AuthenticatedAppPage>
  );
}
